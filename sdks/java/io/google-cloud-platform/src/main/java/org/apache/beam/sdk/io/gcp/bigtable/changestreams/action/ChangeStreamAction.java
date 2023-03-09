/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.bigtable.changestreams.action;

import static org.apache.beam.sdk.io.gcp.bigtable.changestreams.ByteStringRangeHelper.formatByteStringRange;

import com.google.cloud.bigtable.data.v2.models.ChangeStreamContinuationToken;
import com.google.cloud.bigtable.data.v2.models.ChangeStreamMutation;
import com.google.cloud.bigtable.data.v2.models.ChangeStreamRecord;
import com.google.cloud.bigtable.data.v2.models.CloseStream;
import com.google.cloud.bigtable.data.v2.models.Heartbeat;
import com.google.cloud.bigtable.data.v2.models.Range;
import com.google.protobuf.ByteString;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.io.gcp.bigtable.changestreams.ChangeStreamMetrics;
import org.apache.beam.sdk.io.gcp.bigtable.changestreams.TimestampConverter;
import org.apache.beam.sdk.io.gcp.bigtable.changestreams.model.PartitionRecord;
import org.apache.beam.sdk.io.gcp.bigtable.changestreams.restriction.StreamProgress;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.splittabledofn.ManualWatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.values.KV;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This class is responsible for processing individual ChangeStreamRecord. */
@SuppressWarnings({"UnusedVariable", "UnusedMethod"})
@Internal
public class ChangeStreamAction {
  private static final Logger LOG = LoggerFactory.getLogger(ChangeStreamAction.class);

  private final ChangeStreamMetrics metrics;

  /**
   * Constructs ChangeStreamAction to process individual ChangeStreamRecord.
   *
   * @param metrics record beam metrics.
   */
  public ChangeStreamAction(ChangeStreamMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * This class processes ReadChangeStreamResponse from bigtable server. There are 3 possible
   * response types, Heartbeat, ChangeStreamMutation, CloseStream.
   *
   * <ul>
   *   <li>Heartbeat happens periodically based on the initial configuration set at the start of the
   *       beam pipeline. Heartbeat can advance the watermark forward and includes a continuation
   *       token that provides a point to resume from after a checkpoint.
   *   <li>ChangeStreamMutation includes the actual mutation that took place in the Bigtable.
   *       ChangeStreamMutation also includes watermark and continuation token. All
   *       ChangeStreamMutation are emitted to the outputReceiver with the timestamp of 0 (instead
   *       of the commit timestamp). Setting the timestamp to 0 discourages the use of windowing on
   *       this connector. All ChangeStreamMutations will be late data when windowing. This design
   *       decision prefers availability over consistency in the event that partitions are streamed
   *       slowly (due to an outages or other unavailabilities) the commit timestamp which drives
   *       the watermark may lag behind preventing windows from closing.
   *   <li>CloseStream indicates that the stream has come to an end. The CloseStream is not
   *       processed but stored in the RestrictionTracker to be processed later. This ensures that
   *       we successfully commit all pending ChangeStreamMutations.
   * </ul>
   *
   * CloseStream is the only response that type will initiate a resume. Other response type will
   * simply process the response and return empty. Returning empty signals to caller that we have
   * processed the response, and it does not require any additional action.
   *
   * <p>There are 2 cases that cause this function to return a non-empty ProcessContinuation.
   *
   * <ol>
   *   <li>We fail to claim a RestrictionTracker. This can happen for a runner-initiated checkpoint.
   *       When the runner initiates a checkpoint, we will stop and checkpoint pending
   *       ChangeStreamMutations and resume from the previous RestrictionTracker.
   *   <li>The response is a CloseStream. RestrictionTracker claims the CloseStream. We don't do any
   *       additional processing of the response. We return resume to signal to the caller that to
   *       checkpoint all pending ChangeStreamMutations. We expect the caller to check the
   *       RestrictionTracker includes a CloseStream and process it to close the stream.
   * </ol>
   *
   * @param partitionRecord the stream partition that generated the response
   * @param record the change stream record to be processed
   * @param tracker restrictionTracker that we use to claim next block and also to store CloseStream
   * @param receiver to output DataChange
   * @param watermarkEstimator manually progress watermark when processing responses with watermark
   * @return Optional.of(ProcessContinuation) if the run should be stopped or resumed, otherwise
   *     Optional.empty() to do nothing.
   */
  public Optional<DoFn.ProcessContinuation> run(
      PartitionRecord partitionRecord,
      ChangeStreamRecord record,
      RestrictionTracker<StreamProgress, StreamProgress> tracker,
      DoFn.OutputReceiver<KV<ByteString, ChangeStreamMutation>> receiver,
      ManualWatermarkEstimator<Instant> watermarkEstimator,
      boolean shouldDebug) {
    if (record instanceof Heartbeat) {
      Heartbeat heartbeat = (Heartbeat) record;
      StreamProgress streamProgress =
          new StreamProgress(
              heartbeat.getChangeStreamContinuationToken(), heartbeat.getLowWatermark());
      final Instant watermark = TimestampConverter.toInstant(heartbeat.getLowWatermark());
      watermarkEstimator.setWatermark(watermark);

      if (shouldDebug) {
        LOG.info(
            "RCSP {}: Heartbeat partition: {} token: {} watermark: {}",
            formatByteStringRange(partitionRecord.getPartition()),
            formatByteStringRange(heartbeat.getChangeStreamContinuationToken().getPartition()),
            heartbeat.getChangeStreamContinuationToken().getToken(),
            heartbeat.getLowWatermark());
      }
      // If the tracker fail to claim the streamProgress, it most likely means the runner initiated
      // a checkpoint. See {@link
      // org.apache.beam.sdk.io.gcp.bigtable.changestreams.restriction.ReadChangeStreamPartitionProgressTracker}
      // for more information regarding runner initiated checkpoints.
      if (!tracker.tryClaim(streamProgress)) {
        if (shouldDebug) {
          LOG.info(
              "RCSP {}: Failed to claim heart beat tracker",
              formatByteStringRange(partitionRecord.getPartition()));
        }
        return Optional.of(DoFn.ProcessContinuation.stop());
      }
      metrics.incHeartbeatCount();
    } else if (record instanceof CloseStream) {
      CloseStream closeStream = (CloseStream) record;
      StreamProgress streamProgress = new StreamProgress(closeStream);

      if (shouldDebug) {
        LOG.info(
            "RCSP {}: CloseStream: {}",
            formatByteStringRange(partitionRecord.getPartition()),
            closeStream.getChangeStreamContinuationTokens().stream()
                .map(
                    c ->
                        "{partition: "
                            + formatByteStringRange(c.getPartition())
                            + " token: "
                            + c.getToken()
                            + "}")
                .collect(Collectors.joining(", ", "[", "]")));
      }
      // If the tracker fail to claim the streamProgress, it most likely means the runner initiated
      // a checkpoint. See {@link
      // org.apache.beam.sdk.io.gcp.bigtable.changestreams.restriction.ReadChangeStreamPartitionProgressTracker}
      // for more information regarding runner initiated checkpoints.
      if (!tracker.tryClaim(streamProgress)) {
        if (shouldDebug) {
          LOG.info(
              "RCSP {}: Failed to claim close stream tracker",
              formatByteStringRange(partitionRecord.getPartition()));
        }
        return Optional.of(DoFn.ProcessContinuation.stop());
      }
      metrics.incClosestreamCount();
      return Optional.of(DoFn.ProcessContinuation.resume());
    } else if (record instanceof ChangeStreamMutation) {
      ChangeStreamMutation changeStreamMutation = (ChangeStreamMutation) record;
      final Instant watermark =
          TimestampConverter.toInstant(changeStreamMutation.getLowWatermark());
      watermarkEstimator.setWatermark(watermark);
      // Build a new StreamProgress with the continuation token to be claimed.
      ChangeStreamContinuationToken changeStreamContinuationToken =
          new ChangeStreamContinuationToken(
              Range.ByteStringRange.create(
                  partitionRecord.getPartition().getStart(),
                  partitionRecord.getPartition().getEnd()),
              changeStreamMutation.getToken());
      StreamProgress streamProgress =
          new StreamProgress(changeStreamContinuationToken, changeStreamMutation.getLowWatermark());
      // If the tracker fail to claim the streamProgress, it most likely means the runner initiated
      // a checkpoint. See ReadChangeStreamPartitionProgressTracker for more information regarding
      // runner initiated checkpoints.
      if (!tracker.tryClaim(streamProgress)) {
        if (shouldDebug) {
          LOG.info(
              "RCSP {}: Failed to claim data change tracker",
              formatByteStringRange(partitionRecord.getPartition()));
        }
        return Optional.of(DoFn.ProcessContinuation.stop());
      }
      if (changeStreamMutation.getType() == ChangeStreamMutation.MutationType.GARBAGE_COLLECTION) {
        metrics.incChangeStreamMutationGcCounter();
      } else if (changeStreamMutation.getType() == ChangeStreamMutation.MutationType.USER) {
        metrics.incChangeStreamMutationUserCounter();
      }
      Instant delay = TimestampConverter.toInstant(changeStreamMutation.getCommitTimestamp());
      metrics.updateProcessingDelayFromCommitTimestamp(
          Instant.now().getMillis() - delay.getMillis());

      KV<ByteString, ChangeStreamMutation> outputRecord =
          KV.of(changeStreamMutation.getRowKey(), changeStreamMutation);
      // We are outputting elements with timestamp of 0 to prevent reliance on event time. This
      // limits the ability to window on commit time of any data changes. It is still possible to
      // window on processing time.
      receiver.outputWithTimestamp(outputRecord, Instant.EPOCH);
    } else {
      LOG.warn(
          "RCSP {}: Invalid response type", formatByteStringRange(partitionRecord.getPartition()));
    }
    return Optional.empty();
  }
}
