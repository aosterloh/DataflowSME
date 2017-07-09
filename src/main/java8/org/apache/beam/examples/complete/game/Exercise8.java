/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.beam.examples.complete.game;

import static org.apache.beam.sdk.transforms.windowing.TimestampCombiner.END_OF_WINDOW;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.examples.complete.game.utils.GameEvent;
import org.apache.beam.examples.complete.game.utils.Options;
import org.apache.beam.examples.complete.game.utils.PlayEvent;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.ApproximateQuantiles;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.Sessions;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eight in a series of coding exercises in a gaming domain.
 *
 * <p>This exercise is about fixing a bug.
 *
 * <p>See README.md for details.
 */
public class Exercise8 {

  private static final String TIMESTAMP_ATTRIBUTE = "timestamp_ms";
  private static final String MESSAGE_ID_ATTRIBUTE = "unique_id";
  private static final int GLOBAL_LATENCY_QUANTILES = 21;
  private static final int GLOBAL_AGGREGATE_FANOUT = 16;
  private static final int GLOBAL_AGGREGATE_TRIGGER_SEC = 30;
  private static final Logger LOG = LoggerFactory.getLogger(Exercise8.class);

  private static final TupleTag<PlayEvent> playTag = new TupleTag<PlayEvent>();
  private static final TupleTag<GameEvent> eventTag = new TupleTag<GameEvent>();

  /**
   * Options supported by {@link Exercise8}.
   */
  interface Exercise8Options extends Options, StreamingOptions {

    @Description("Pub/Sub topic to read from")
    @Validation.Required
    String getTopic();

    void setTopic(String value);

    @Description("Pub/Sub play events topic to read from")
    @Validation.Required
    String getPlayEventsTopic();

    void setPlayEventsTopic(String value);

    @Description("Numeric value of gap between user sessions, in minutes")
    @Default.Integer(5)
    Integer getSessionGap();

    void setSessionGap(Integer value);
  }

  // BUG1 : parser exceptions
  public static class BuggyParseEventFn extends DoFn<String, GameEvent> {

    @ProcessElement
    public void processElement(ProcessContext c) {
      String[] components = c.element().split(",");
      String user = components[0].trim();
      String team = components[1].trim();
      Integer score = Integer.parseInt(components[2].trim());
      Long timestamp = Long.parseLong(components[3].trim());
      String eventId = components[5].trim();
      GameEvent gInfo = new GameEvent(user, team, score, timestamp, eventId);
      c.output(gInfo);
    }
  }

  public static class BuggyParsePlayEventFn extends DoFn<String, PlayEvent> {

    @ProcessElement
    public void processElement(ProcessContext c) {
      String[] components = c.element().split(",");
      try {
        String user = components[0].trim();
        Long timestamp = Long.parseLong(components[1].trim());
        String eventId = components[3].trim();
        PlayEvent play = new PlayEvent(user, timestamp, eventId);
        c.output(play);
        // BUG2: Logging is expensive and slows down processing.
        for (int i = 0; i < 100; ++i) {
          LOG.info("Unnecessarily frequent logging about event " + eventId);
        }
      } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
        LOG.info("Parse error on " + c.element() + ", " + e.getMessage());
      }
    }
  }

  public static class ComputeLatencyFn extends DoFn<KV<String, CoGbkResult>, KV<String, Long>> {

    private final Counter numDroppedSessionsNoEvent = Metrics
        .counter("main", "DroppedSessionsNoEvent");
    private final Counter numDroppedSessionsTooManyEvents = Metrics
        .counter("main", "DroppedSessionsTooManyEvents");
    private final Counter numDroppedSessionsNoPlayEvents = Metrics
        .counter("main", "DroppedSessionsNoPlayEvents");

    @ProcessElement
    public void processElement(ProcessContext c) {
      // BUG3: Expensive blocking calls (eg RPC to an external service) on every element.
      someVeryExpensiveBlockingCall();
      Iterable<PlayEvent> plays = c.element().getValue().getAll(playTag);
      Iterable<GameEvent> events = c.element().getValue().getAll(eventTag);
      int playCount = 0;
      Long maxPlayEventTs = 0L;
      for (PlayEvent play : plays) {
        playCount++;
        maxPlayEventTs = Math.max(maxPlayEventTs, play.getTimestamp());
      }
      int eventCount = 0;
      GameEvent event = null;
      for (GameEvent currentEvent : events) {
        eventCount++;
        event = currentEvent;
      }
      String id = c.element().getKey();
      if (eventCount == 0) {
        numDroppedSessionsNoEvent.inc();
      } else if (eventCount > 1) {
        numDroppedSessionsTooManyEvents.inc();
      } else if (playCount == 0) {
        numDroppedSessionsNoPlayEvents.inc();
      } else {
        Long minLatency = event.getTimestamp() - maxPlayEventTs;
        c.output(KV.of(event.getUser(), minLatency));
      }
    }

    public void someVeryExpensiveBlockingCall() {
      try {
        Thread.sleep(300000);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Exercise8Options options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(Exercise8Options.class);
    // Enforce that this pipeline is always run in streaming mode.
    options.setStreaming(true);
    options.setRunner(DataflowRunner.class);
    Pipeline pipeline = Pipeline.create(options);

    TableReference badUserTable = new TableReference();
    badUserTable.setDatasetId(options.getOutputDataset());
    badUserTable.setProjectId(options.as(GcpOptions.class).getProject());
    badUserTable.setTableId(options.getOutputTableName() + "_bad_users");

    // Read Events from Pub/Sub using custom timestamps and custom message id label.
    PCollection<KV<String, GameEvent>> sessionedEvents =
        pipeline
            .apply(
                "ReadGameScoreEvents",
                PubsubIO.readStrings().withTimestampAttribute(TIMESTAMP_ATTRIBUTE)
                    .withIdAttribute(MESSAGE_ID_ATTRIBUTE).fromTopic(options.getTopic()))
            .apply("ParseGameScoreEvents", ParDo.of(new BuggyParseEventFn()))
            .apply(
                "KeyGameScoreByEventId",
                WithKeys.of((GameEvent event) -> event.getEventId())
                    .withKeyType(TypeDescriptor.of(String.class)))
            .apply(
                "SessionizeGameScoreEvents",
                Window.<KV<String, GameEvent>>into(
                    Sessions.withGapDuration(Duration.standardMinutes(options.getSessionGap())))
                    .withTimestampCombiner(END_OF_WINDOW));

    // Read PlayEvents from Pub/Sub using custom timestamps and custom message id label.
    PCollection<KV<String, PlayEvent>> sessionedPlayEvents =
        pipeline
            .apply(
                "ReadGamePlayEvents",
                PubsubIO.readStrings().withTimestampAttribute(TIMESTAMP_ATTRIBUTE)
                    .withIdAttribute(MESSAGE_ID_ATTRIBUTE)
                    .fromTopic(options.getPlayEventsTopic()))
            .apply("ParseGamePlayEvents", ParDo.of(new BuggyParsePlayEventFn()))
            .apply(
                "KeyGamePlayByEventId",
                WithKeys.of((PlayEvent play) -> play.getEventId())
                    .withKeyType(TypeDescriptor.of(String.class)))
            .apply(
                "SessionizeGamePlayEvents",
                Window.<KV<String, PlayEvent>>into(
                    Sessions.withGapDuration(Duration.standardMinutes(options.getSessionGap())))
                    .withTimestampCombiner(END_OF_WINDOW));

    // Compute per-user latency.
    PCollection<KV<String, Long>> userLatency =
        KeyedPCollectionTuple.of(playTag, sessionedPlayEvents)
            .and(eventTag, sessionedEvents)
            .apply("JoinScorePlayEvents", CoGroupByKey.create())
            .apply("ComputeLatency", ParDo.of(new ComputeLatencyFn()));

    // Create a view onto quantiles of the global latency distribution.
    PCollectionView<List<Long>> globalQuantiles =
        userLatency
            .apply("GetLatencies", Values.create())
            // Re-window session results into a global window, and trigger periodically making sure
            // to use the full accumulated window contents.
            .apply(
                "GlobalWindowRetrigger",
                Window.<Long>into(new GlobalWindows())
                    .triggering(
                        Repeatedly.forever(
                            AfterProcessingTime.pastFirstElementInPane()
                                .plusDelayOf(
                                    Duration.standardSeconds(GLOBAL_AGGREGATE_TRIGGER_SEC))))
                    .accumulatingFiredPanes())
            .apply(
                ((Combine.Globally<Long, List<Long>>)
                    ApproximateQuantiles.<Long>globally(GLOBAL_LATENCY_QUANTILES))
                    .withFanout(GLOBAL_AGGREGATE_FANOUT)
                    .asSingletonView());

    userLatency
        // Use the computed latency distribution as a side-input to filter out likely bad users.
        .apply(
            "DetectBadUsers",
            ParDo.of(
                new DoFn<KV<String, Long>, String>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    String user = c.element().getKey();
                    Long latency = c.element().getValue();
                    List<Long> quantiles = c.sideInput(globalQuantiles);
                    // Users in the first quantile are considered spammers, since their
                    // score to play event latency is too low, suggesting a robot.
                    if (latency < quantiles.get(1)) {
                      c.output(user);
                    }
                  }
                }).withSideInputs(globalQuantiles)
        )
        // We want to only emit a single BigQuery row for every bad user. To do this, we
        // re-key by user, then window globally and trigger on the first element for each key.
        .apply(
            "KeyByUser",
            WithKeys.of((String user) -> user).withKeyType(TypeDescriptor.of(String.class)))
        .apply(
            "GlobalWindowsTriggerOnFirst",
            Window.<KV<String, String>>into(new GlobalWindows())
                .triggering(
                    AfterProcessingTime.pastFirstElementInPane()
                        .plusDelayOf(Duration.standardSeconds(10)))
                .accumulatingFiredPanes())
        .apply("GroupByUser", GroupByKey.<String, String>create())
        .apply("FormatBadUsers", ParDo.of(new FormatBadUserFn()))
        .apply(
            "WriteBadUsers",
            BigQueryIO.writeTableRows().to(badUserTable)
                .withSchema(FormatBadUserFn.getSchema())
                .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
                .withWriteDisposition(WriteDisposition.WRITE_APPEND));

    userLatency
        .apply(
            "ReKeyFn",
            // BUG4: We have a hot key. Especially when the cost of downstream fn is high, must
            // ensure we have good sharding.
            WithKeys.of((KV<String, Long> item) -> "").withKeyType(TypeDescriptor.of(String.class)))
        .apply(
            "WindowAndTriggerOften",
            Window.<KV<String, KV<String, Long>>>into(new GlobalWindows())
                .triggering(
                    Repeatedly.forever(
                        AfterProcessingTime.pastFirstElementInPane()
                            .plusDelayOf(Duration.standardSeconds(10))))
                .discardingFiredPanes())
        .apply("GroupByNewKey", GroupByKey.<String, KV<String, Long>>create())
        .apply("DoExpensiveWork", ParDo.of(new ExpensiveWorkPerElement()));

    PipelineResult result = pipeline.run();
    result.waitUntilFinish();
  }

  /**
   * Format a KV of user and associated properties to a BigQuery TableRow.
   */
  static class FormatBadUserFn extends DoFn<KV<String, Iterable<String>>, TableRow> {

    @ProcessElement
    public void processElement(ProcessContext c) {
      TableRow row =
          new TableRow()
              .set("bad_user", c.element().getKey())
              .set("time", Instant.now().getMillis() / 1000);
      c.output(row);
    }

    static TableSchema getSchema() {
      List<TableFieldSchema> fields = new ArrayList<>();
      fields.add(new TableFieldSchema().setName("bad_user").setType("STRING"));
      fields.add(new TableFieldSchema().setName("time").setType("TIMESTAMP"));
      return new TableSchema().setFields(fields);
    }
  }

  public static class ExpensiveWorkPerElement<K, V>
      extends DoFn<KV<K, Iterable<V>>, KV<K, Iterable<V>>> {

    @ProcessElement
    public void processElement(ProcessContext c) {
      for (V item : c.element().getValue()) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
      c.output(KV.of(c.element().getKey(), c.element().getValue()));
    }
  }
}
