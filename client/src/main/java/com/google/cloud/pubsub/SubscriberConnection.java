/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsub;

import com.google.auth.Credentials;
import com.google.cloud.pubsub.Subscriber.MessageReceiver;
import com.google.cloud.pubsub.Subscriber.MessageReceiver.AckReply;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.SubscriberGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ClientResponseObserver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of {@link Subscriber}. */
final class SubscriberConnection extends AbstractService {
  private static final Logger logger = LoggerFactory.getLogger(SubscriberConnection.class);

  private static final Duration INITIAL_CHANNEL_RECONNECT_BACKOFF = new Duration(100); // 100ms
  private static final int MAX_PER_REQUEST_CHANGES = 10000;
  private static final int MIN_ACK_DEADLINE_SECONDS = 10;
  private static final int MAX_ACK_DEADLINE_SECONDS = 600;
  private static final int INITIAL_ACK_DEADLINE_SECONDS = 10;
  private static final int INITIAL_ACK_DEADLINE_EXTENSION_SECONDS = 2;
  private static final Duration ACK_DEADLINE_UPDATE_PERIOD = Duration.standardMinutes(1);
  private static final double PERCENTILE_FOR_ACK_DEADLINE_UPDATES = 99.9;
  private static final Duration PENDING_ACKS_SEND_DELAY = Duration.millis(100);

  private static enum SubscriberState {
    CREATED,
    STARTED,
    SHUTDOWN
  }

  private final Duration ackExpirationPadding;
  private final ScheduledExecutorService executor;
  private final MessageReceiver receiver;
  private final String subscription;
  private Duration channelReconnectBackoff = INITIAL_CHANNEL_RECONNECT_BACKOFF;

  private int streamAckDeadlineSeconds;
  private final FlowController flowController;
  private final MessagesWaiter messagesWaiter;

  // Map of outstanding messages (value) ordered by expiration time (key) in ascending order.
  private final Multimap<ExpirationInfo, AckHandler> outstandingAckHandlers;
  private final Set<String> pendingAcks;
  private final Set<String> pendingNacks;

  private final Channel channel;
  private final Credentials credentials;

  private ClientCallStreamObserver<StreamingPullRequest> requestObserver;

  private final Lock alarmsLock;
  private ScheduledFuture<?> ackDeadlineExtensionAlarm;
  private Instant nextAckDeadlineExtensionAlarmTime;
  private ScheduledFuture<?> pendingAcksAlarm;

  private ScheduledFuture<?> ackDeadlineUpdater;
  // To keep track of number of seconds the receiver takes to process messages.
  private final Distribution ackLatencyDistribution;

  public SubscriberConnection(
      String subscription,
      Credentials credentials,
      MessageReceiver receiver,
      Duration ackExpirationPadding,
      Channel channel,
      FlowController flowController,
      ScheduledExecutorService executor) {
    this.executor = executor;
    this.credentials = credentials;
    this.ackExpirationPadding = ackExpirationPadding;
    streamAckDeadlineSeconds =
        Math.max(
            INITIAL_ACK_DEADLINE_SECONDS,
            Ints.saturatedCast(ackExpirationPadding.getStandardSeconds()));
    this.receiver = receiver;
    this.subscription = subscription;
    this.flowController = flowController;
    outstandingAckHandlers = TreeMultimap.create();
    pendingAcks = new HashSet<>(MAX_PER_REQUEST_CHANGES * 2);
    pendingNacks = new HashSet<>(MAX_PER_REQUEST_CHANGES * 2);
    // 601 buckets of 1s resolution from 0s to MAX_ACK_DEADLINE_SECONDS
    ackLatencyDistribution = new Distribution(MAX_ACK_DEADLINE_SECONDS + 1);
    this.channel = channel;
    alarmsLock = new ReentrantLock();
    nextAckDeadlineExtensionAlarmTime = new Instant(Long.MAX_VALUE);
    messagesWaiter = new MessagesWaiter();
  }

  private static class ExpirationInfo implements Comparable<ExpirationInfo> {
    Instant expiration;
    int nextExtensionSeconds;

    ExpirationInfo(Instant expiration, int initialAckDeadlineExtension) {
      this.expiration = expiration;
      nextExtensionSeconds = initialAckDeadlineExtension;
    }

    void extendExpiration() {
      expiration = Instant.now().plus(Duration.standardSeconds(nextExtensionSeconds));
      nextExtensionSeconds = 2 * nextExtensionSeconds;
    }

    @Override
    public int hashCode() {
      return expiration.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ExpirationInfo)) {
        return false;
      }

      ExpirationInfo other = (ExpirationInfo) obj;
      return expiration.equals(other.expiration);
    }

    @Override
    public int compareTo(ExpirationInfo other) {
      return expiration.compareTo(other.expiration);
    }
  }

  /** Stores the data needed to asynchronously modify acknowledgement deadlines. */
  private static class PendingModifyAckDeadline {
    final String ackId;
    final int deadlineExtensionSeconds;

    PendingModifyAckDeadline(String ackId, int deadlineExtensionSeconds) {
      this.ackId = ackId;
      this.deadlineExtensionSeconds = deadlineExtensionSeconds;
    }
  }

  /** Handles callbacks for acking/nacking messages from the {@link MessageReceiver}. */
  private class AckHandler implements FutureCallback<AckReply>, Comparable<AckHandler> {
    private final String ackId;
    private final int outstandingBytes;
    private final AtomicBoolean acked;
    private final Instant receivedTime;

    AckHandler(String ackId, int outstandingBytes) {
      this.ackId = ackId;
      this.outstandingBytes = outstandingBytes;
      acked = new AtomicBoolean(false);
      receivedTime = Instant.now();
    }

    @Override
    public int compareTo(AckHandler arg0) {
      // We don't care about the ordering between AcknowledgeHandlers.
      return 0;
    }

    @Override
    public void onFailure(Throwable t) {
      logger.warn(
          "MessageReceiver failed to processes ack ID: " + ackId + ", the message will be nacked.",
          t);
      synchronized (pendingNacks) {
        pendingNacks.add(ackId);
      }
      setupPendingAcksAlarm();
      flowController.release(1, outstandingBytes);
      messagesWaiter.incrementPendingMessages(-1);
    }

    @Override
    public void onSuccess(AckReply reply) {
      acked.getAndSet(true);
      switch (reply) {
        case ACK:
          synchronized (pendingAcks) {
            pendingAcks.add(ackId);
          }
          setupPendingAcksAlarm();
          flowController.release(1, outstandingBytes);
          // Record the latency rounded to the next closest integer.
          ackLatencyDistribution.record(
              Ints.saturatedCast(
                  (long) Math.ceil(new Duration(receivedTime, Instant.now()).getMillis() / 1000D)));
          messagesWaiter.incrementPendingMessages(-1);
          return;
        case NACK:
          synchronized (pendingNacks) {
            pendingNacks.add(ackId);
          }
          setupPendingAcksAlarm();
          flowController.release(1, outstandingBytes);
          messagesWaiter.incrementPendingMessages(-1);
          return;
      }
      throw new IllegalArgumentException(String.format("AckReply: %s not supported", reply));
    }
  }

  @Override
  protected void doStart() {
    logger.debug("Starting subscriber.");

    initializeStreaming();

    ackDeadlineUpdater =
        executor.scheduleAtFixedRate(
            new Runnable() {
              @Override
              public void run() {
                // It is guaranteed this will be <= MAX_ACK_DEADLINE_SECONDS, the max of the API.
                long ackLatency =
                    ackLatencyDistribution.getNthPercentile(PERCENTILE_FOR_ACK_DEADLINE_UPDATES);
                if (ackLatency > 0) {
                  int possibleStreamAckDeadlineSeconds =
                      Math.max(
                          MIN_ACK_DEADLINE_SECONDS,
                          Ints.saturatedCast(
                              Math.max(ackLatency, ackExpirationPadding.getStandardSeconds())));
                  if (streamAckDeadlineSeconds != possibleStreamAckDeadlineSeconds) {
                    streamAckDeadlineSeconds = possibleStreamAckDeadlineSeconds;
                    logger.debug(
                        "Updating stream deadline to {} seconds.", streamAckDeadlineSeconds);
                    requestObserver.onNext(
                        StreamingPullRequest.newBuilder()
                            .setStreamAckDeadlineSeconds(streamAckDeadlineSeconds)
                            .build());
                  }
                }
              }
            },
            ACK_DEADLINE_UPDATE_PERIOD.getMillis(),
            ACK_DEADLINE_UPDATE_PERIOD.getMillis(),
            TimeUnit.MILLISECONDS);
    notifyStarted();
  }

  @Override
  protected void doStop() {
    messagesWaiter.waitNoMessages();
    alarmsLock.lock();
    try {
      if (ackDeadlineExtensionAlarm != null) {
        ackDeadlineExtensionAlarm.cancel(true);
        ackDeadlineExtensionAlarm = null;
      }
    } finally {
      alarmsLock.unlock();
    }
    sendOutstandingAckOperations();
    ackDeadlineUpdater.cancel(true);
    requestObserver.onError(Status.CANCELLED.asException());
    notifyStopped();
  }

  private void initializeStreaming() {
    final SettableFuture<Void> errorFuture = SettableFuture.create();
    final ClientResponseObserver<StreamingPullRequest, StreamingPullResponse> responseObserver =
        new ClientResponseObserver<StreamingPullRequest, StreamingPullResponse>() {
          @Override
          public void beforeStart(ClientCallStreamObserver<StreamingPullRequest> requestObserver) {
            SubscriberConnection.this.requestObserver = requestObserver;
            requestObserver.disableAutoInboundFlowControl();
          }

          @Override
          public void onNext(StreamingPullResponse response) {
            processReceivedMessages(response);
          }

          @Override
          public void onError(Throwable t) {
            logger.debug("Terminated streaming with exception", t);
            errorFuture.setException(t);
          }

          @Override
          public void onCompleted() {
            logger.debug("Streaming pull terminated successfully!");
            errorFuture.set(null);
          }
        };
    final ClientCallStreamObserver<StreamingPullRequest> requestObserver =
        (ClientCallStreamObserver<StreamingPullRequest>)
            (ClientCalls.asyncBidiStreamingCall(
                channel.newCall(
                    SubscriberGrpc.METHOD_STREAMING_PULL,
                    CallOptions.DEFAULT.withCallCredentials(MoreCallCredentials.from(credentials))),
                    responseObserver));
    logger.debug(
        "Initializing stream to subscription {} with deadline {}",
        subscription,
        streamAckDeadlineSeconds);
    requestObserver.onNext(
        StreamingPullRequest.newBuilder()
            .setSubscription(subscription)
            .setStreamAckDeadlineSeconds(streamAckDeadlineSeconds)
            .build());
    requestObserver.request(1);

    Futures.addCallback(
        errorFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(@Nullable Void result) {
            channelReconnectBackoff = INITIAL_CHANNEL_RECONNECT_BACKOFF;
            // The stream was closed. And any case we want to reopen it to continue receiving
            // messages.
            initializeStreaming();
          }

          @Override
          public void onFailure(Throwable t) {
            Status errorStatus = Status.fromThrowable(t);
            if (isRetryable(errorStatus) && isAlive()) {
              long backoffMillis = channelReconnectBackoff.getMillis();
              channelReconnectBackoff = channelReconnectBackoff.plus(backoffMillis);
              executor.schedule(
                  new Runnable() {
                    @Override
                    public void run() {
                      initializeStreaming();
                    }
                  },
                  backoffMillis,
                  TimeUnit.MILLISECONDS);
            } else {
              notifyFailed(t);
            }
          }
        },
        executor);
  }

  private boolean isAlive() {
    return state() == State.RUNNING || state() == State.STARTING;
  }

  private boolean isRetryable(Status status) {
    switch (status.getCode()) {
      case DEADLINE_EXCEEDED:
      case INTERNAL:
      case CANCELLED:
      case RESOURCE_EXHAUSTED:
      case UNAVAILABLE:
        return true;
      default:
        return false;
    }
  }

  private void processReceivedMessages(StreamingPullResponse response) {
    final List<com.google.pubsub.v1.ReceivedMessage> responseMessages =
        response.getReceivedMessagesList();
    try {
      Instant now = Instant.now();
      int receivedMessagesCount = response.getReceivedMessagesCount();
      int totalByteCount = 0;
      final List<AckHandler> ackHandlers = new ArrayList<>(responseMessages.size());
      for (ReceivedMessage pubsubMessage : responseMessages) {
        int messageSize = pubsubMessage.getMessage().getSerializedSize();
        totalByteCount += messageSize;
        ackHandlers.add(new AckHandler(pubsubMessage.getAckId(), messageSize));
      }
      ExpirationInfo expiration =
          new ExpirationInfo(
              now.plus(streamAckDeadlineSeconds * 1000), INITIAL_ACK_DEADLINE_EXTENSION_SECONDS);
      synchronized (outstandingAckHandlers) {
        outstandingAckHandlers.putAll(expiration, ackHandlers);
      }
      logger.debug("Received {} messages at {}", responseMessages.size(), now);
      setupNextAckDeadlineExtensionAlarm(expiration);

      messagesWaiter.incrementPendingMessages(responseMessages.size());
      Iterator<AckHandler> acksIterator = ackHandlers.iterator();
      for (ReceivedMessage userMessage : responseMessages) {
        final PubsubMessage message = userMessage.getMessage();
        final AckHandler ackHandler = acksIterator.next();
        executor.submit(
            new Runnable() {
              @Override
              public void run() {
                Futures.addCallback(receiver.receiveMessage(message), ackHandler);
              }
            });
      }
      flowController.reserve(receivedMessagesCount, totalByteCount);
      // Only if not shutdown we will request one more batch of messages to be delivered.
      if (isAlive()) {
        requestObserver.request(1);
      }
    } catch (Exception e) {
      requestObserver.onError(e);
    }
  }

  private void setupPendingAcksAlarm() {
    alarmsLock.lock();
    try {
      if (pendingAcksAlarm == null) {
        pendingAcksAlarm =
            executor.schedule(
                new Runnable() {
                  @Override
                  public void run() {
                    alarmsLock.lock();
                    try {
                      pendingAcksAlarm = null;
                    } finally {
                      alarmsLock.unlock();
                    }
                    sendOutstandingAckOperations();
                  }
                },
                PENDING_ACKS_SEND_DELAY.getMillis(),
                TimeUnit.MILLISECONDS);
      }
    } finally {
      alarmsLock.unlock();
    }
  }

  private void setupNextAckDeadlineExtensionAlarm(ExpirationInfo messageExpiration) {
    Instant possibleNextAlarmTime = messageExpiration.expiration.minus(ackExpirationPadding);
    alarmsLock.lock();
    try {
      if (nextAckDeadlineExtensionAlarmTime.isAfter(possibleNextAlarmTime)) {
        logger.debug(
            "Scheduling next alarm time: {}, last alarm set to time: {}",
            possibleNextAlarmTime,
            nextAckDeadlineExtensionAlarmTime);
        if (ackDeadlineExtensionAlarm != null) {
          logger.debug("Canceling previous alarm");
          ackDeadlineExtensionAlarm.cancel(false);
        }

        nextAckDeadlineExtensionAlarmTime = possibleNextAlarmTime;

        ackDeadlineExtensionAlarm =
            executor.schedule(
                new Runnable() {
                  @Override
                  public void run() {
                    alarmsLock.lock();
                    try {
                      nextAckDeadlineExtensionAlarmTime = new Instant(Long.MAX_VALUE);
                      ackDeadlineExtensionAlarm = null;
                      pendingAcksAlarm.cancel(false);
                      pendingAcksAlarm = null;
                    } finally {
                      alarmsLock.unlock();
                    }

                    Instant now = Instant.now();
                    // Rounded to the next second, so we only schedule future alarms at the second
                    // resolution.
                    Instant cutOverTime =
                        new Instant(
                            ((long)
                                    Math.ceil(
                                        now.plus(ackExpirationPadding).plus(500).getMillis()
                                            / 1000.0))
                                * 1000L);
                    logger.debug(
                        "Running alarm sent outstanding acks, at now time: {}, with cutover "
                            + "time: {}, padding: {}",
                        now,
                        cutOverTime,
                        ackExpirationPadding);
                    ExpirationInfo nextScheduleExpiration = null;
                    List<PendingModifyAckDeadline> modifyAckDeadlinesToSend = new ArrayList<>();

                    synchronized (outstandingAckHandlers) {
                      Iterator<ExpirationInfo> expirationsIterator =
                          outstandingAckHandlers.keySet().iterator();
                      while (expirationsIterator.hasNext() && nextScheduleExpiration == null) {
                        ExpirationInfo messageExpiration = expirationsIterator.next();
                        if (messageExpiration.expiration.compareTo(cutOverTime) <= 0) {
                          Collection<AckHandler> expiringAcks =
                              outstandingAckHandlers.get(messageExpiration);
                          List<AckHandler> renewedAckHandlers =
                              new ArrayList<>(expiringAcks.size());
                          Iterator<AckHandler> expiringAcksIterator = expiringAcks.iterator();
                          messageExpiration.extendExpiration();
                          int extensionSeconds =
                              Ints.saturatedCast(
                                  new Interval(now, messageExpiration.expiration)
                                      .toDuration()
                                      .getStandardSeconds());
                          while (expiringAcksIterator.hasNext()) {
                            AckHandler ackHandler = expiringAcksIterator.next();
                            if (ackHandler.acked.get()) {
                              expiringAcksIterator.remove();
                              continue;
                            }
                            modifyAckDeadlinesToSend.add(
                                new PendingModifyAckDeadline(ackHandler.ackId, extensionSeconds));
                            renewedAckHandlers.add(ackHandler);
                          }
                          if (!renewedAckHandlers.isEmpty()) {
                            outstandingAckHandlers.putAll(messageExpiration, renewedAckHandlers);
                          }
                          expirationsIterator.remove();
                        } else {
                          nextScheduleExpiration = messageExpiration;
                          break;
                        }
                      }
                    }

                    sendOutstandingAckOperations(modifyAckDeadlinesToSend);

                    if (nextScheduleExpiration != null) {
                      logger.debug(
                          "Scheduling based on outstanding, now time: {}, "
                              + "next schedule time: {}",
                          now,
                          nextScheduleExpiration);
                      setupNextAckDeadlineExtensionAlarm(nextScheduleExpiration);
                    }
                  }
                },
                nextAckDeadlineExtensionAlarmTime.getMillis() - Instant.now().getMillis(),
                TimeUnit.MILLISECONDS);
      }

    } finally {
      alarmsLock.unlock();
    }
  }

  private void sendOutstandingAckOperations() {
    sendOutstandingAckOperations(new ArrayList<>());
  }

  private void sendOutstandingAckOperations(List<PendingModifyAckDeadline> ackDeadlineExtensions) {
    List<PendingModifyAckDeadline> modifyAckDeadlinesToSend =
        Lists.newArrayList(ackDeadlineExtensions);
    List<String> acksToSend = new ArrayList<>(pendingAcks.size());
    synchronized (pendingAcks) {
      if (!pendingAcks.isEmpty()) {
        try {
          acksToSend = new ArrayList<>(pendingAcks);
          logger.debug("Sending {} acks", acksToSend.size());
        } finally {
          pendingAcks.clear();
        }
      }
    }
    List<PendingModifyAckDeadline> nacksToSend = new ArrayList<>(pendingNacks.size());
    synchronized (pendingNacks) {
      if (!pendingNacks.isEmpty()) {
        try {
          for (String ackId : pendingNacks) {
            modifyAckDeadlinesToSend.add(new PendingModifyAckDeadline(ackId, 0));
          }
          logger.debug("Sending {} nacks", nacksToSend.size());
        } finally {
          pendingNacks.clear();
        }
      }
    }

    // Send the modify ack deadlines in batches as not to exceed the max request
    // size.
    List<List<String>> ackChunks = Lists.partition(acksToSend, MAX_PER_REQUEST_CHANGES);
    List<List<PendingModifyAckDeadline>> modifyAckDeadlineChunks =
        Lists.partition(modifyAckDeadlinesToSend, MAX_PER_REQUEST_CHANGES);
    Iterator<List<String>> ackChunksIt = ackChunks.iterator();
    Iterator<List<PendingModifyAckDeadline>> modifyAckDeadlineChunksIt =
        modifyAckDeadlineChunks.iterator();

    while (ackChunksIt.hasNext() || modifyAckDeadlineChunksIt.hasNext()) {
      com.google.pubsub.v1.StreamingPullRequest.Builder requestBuilder =
          StreamingPullRequest.newBuilder();
      if (modifyAckDeadlineChunksIt.hasNext()) {
        List<PendingModifyAckDeadline> modAckChunk = modifyAckDeadlineChunksIt.next();
        for (PendingModifyAckDeadline modifyAckDeadline : modAckChunk) {
          requestBuilder.addModifyDeadlineSeconds(modifyAckDeadline.deadlineExtensionSeconds);
          requestBuilder.addModifyDeadlineAckIds(modifyAckDeadline.ackId);
        }
      }
      if (ackChunksIt.hasNext()) {
        List<String> ackChunk = ackChunksIt.next();
        requestBuilder.addAllAckIds(ackChunk);
      }
      requestObserver.onNext(requestBuilder.build());
    }
  }
}
