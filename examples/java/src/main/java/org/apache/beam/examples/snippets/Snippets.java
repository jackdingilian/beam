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
package org.apache.beam.examples.snippets;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.api.services.bigquery.model.TimePartitioning;
import com.google.cloud.language.v1.AnnotateTextRequest;
import com.google.cloud.language.v1.AnnotateTextResponse;
import com.google.cloud.language.v1.Document;
import com.google.cloud.language.v1.Entity;
import com.google.cloud.language.v1.Sentence;
import com.google.cloud.language.v1.Token;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.coders.DoubleCoder;
import org.apache.beam.sdk.extensions.avro.coders.AvroCoder;
import org.apache.beam.sdk.extensions.ml.AnnotateText;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryOptions;
import org.apache.beam.sdk.io.gcp.bigquery.DynamicDestinations;
import org.apache.beam.sdk.io.gcp.bigquery.InsertRetryPolicy;
import org.apache.beam.sdk.io.gcp.bigquery.SchemaAndRecord;
import org.apache.beam.sdk.io.gcp.bigquery.TableDestination;
import org.apache.beam.sdk.io.gcp.bigquery.WriteResult;
import org.apache.beam.sdk.io.range.OffsetRange;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.transforms.Join;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.BoundedPerElement;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.PeriodicImpulse;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.Watch;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.TruncateResult;
import org.apache.beam.sdk.transforms.splittabledofn.TimestampObservingWatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.WatermarkEstimator;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.transforms.windowing.WindowMappingFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.sdk.values.ValueInSingleWindow;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Code snippets used in webdocs. */
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness", // TODO(https://github.com/apache/beam/issues/20497)
  // TODO(https://github.com/apache/beam/issues/21230): Remove when new version of
  // errorprone is released (2.11.0)
  "unused"
})
public class Snippets {

  @DefaultCoder(AvroCoder.class)
  static class Quote {
    final String source;
    final String quote;

    public Quote() {
      this.source = "";
      this.quote = "";
    }

    public Quote(String source, String quote) {
      this.source = source;
      this.quote = quote;
    }
  }

  @DefaultCoder(AvroCoder.class)
  static class WeatherData {
    final long year;
    final long month;
    final long day;
    final double maxTemp;

    public WeatherData() {
      this.year = 0;
      this.month = 0;
      this.day = 0;
      this.maxTemp = 0.0f;
    }

    public WeatherData(long year, long month, long day, double maxTemp) {
      this.year = year;
      this.month = month;
      this.day = day;
      this.maxTemp = maxTemp;
    }
  }

  /** Using a Read and Write transform to read/write from/to BigQuery. */
  public static void modelBigQueryIO(Pipeline p) {
    modelBigQueryIO(p, "", "", "");
  }

  public static void modelBigQueryIO(
      Pipeline p, String writeProject, String writeDataset, String writeTable) {
    {
      // [START BigQueryTableSpec]
      String tableSpec = "clouddataflow-readonly:samples.weather_stations";
      // [END BigQueryTableSpec]
    }

    {
      // [START BigQueryTableSpecWithoutProject]
      String tableSpec = "samples.weather_stations";
      // [END BigQueryTableSpecWithoutProject]
    }

    {
      // [START BigQueryTableSpecObject]
      TableReference tableSpec =
          new TableReference()
              .setProjectId("clouddataflow-readonly")
              .setDatasetId("samples")
              .setTableId("weather_stations");
      // [END BigQueryTableSpecObject]
    }

    {
      @SuppressWarnings("ModifiedButNotUsed")
      // [START BigQueryDataTypes]
      TableRow row = new TableRow();
      row.set("string", "abc");
      byte[] rawbytes = {(byte) 0xab, (byte) 0xac};
      row.set("bytes", Base64.getEncoder().encodeToString(rawbytes));
      row.set("integer", 5);
      row.set("float", 0.5);
      row.set("numeric", 5);
      row.set("boolean", true);
      row.set("timestamp", "2018-12-31 12:44:31.744957 UTC");
      row.set("date", "2018-12-31");
      row.set("time", "12:44:31");
      row.set("datetime", "2019-06-11T14:44:31");
      row.set("geography", "POINT(30 10)");
      // [END BigQueryDataTypes]
    }

    {
      String tableSpec = "clouddataflow-readonly:samples.weather_stations";
      // [START BigQueryReadTable]
      PCollection<Double> maxTemperatures =
          p.apply(BigQueryIO.readTableRows().from(tableSpec))
              // Each row is of type TableRow
              .apply(
                  MapElements.into(TypeDescriptors.doubles())
                      .via((TableRow row) -> (Double) row.get("max_temperature")));
      // [END BigQueryReadTable]
    }

    {
      String tableSpec = "clouddataflow-readonly:samples.weather_stations";
      // [START BigQueryReadFunction]
      PCollection<Double> maxTemperatures =
          p.apply(
              BigQueryIO.read(
                      (SchemaAndRecord elem) -> (Double) elem.getRecord().get("max_temperature"))
                  .from(tableSpec)
                  .withCoder(DoubleCoder.of()));
      // [END BigQueryReadFunction]
    }

    {
      // [START BigQueryReadQuery]
      PCollection<Double> maxTemperatures =
          p.apply(
              BigQueryIO.read(
                      (SchemaAndRecord elem) -> (Double) elem.getRecord().get("max_temperature"))
                  .fromQuery(
                      "SELECT max_temperature FROM [clouddataflow-readonly:samples.weather_stations]")
                  .withCoder(DoubleCoder.of()));
      // [END BigQueryReadQuery]
    }

    {
      // [START BigQueryReadQueryStdSQL]
      PCollection<Double> maxTemperatures =
          p.apply(
              BigQueryIO.read(
                      (SchemaAndRecord elem) -> (Double) elem.getRecord().get("max_temperature"))
                  .fromQuery(
                      "SELECT max_temperature FROM `clouddataflow-readonly.samples.weather_stations`")
                  .usingStandardSql()
                  .withCoder(DoubleCoder.of()));
      // [END BigQueryReadQueryStdSQL]
    }

    // [START BigQuerySchemaJson]
    String tableSchemaJson =
        ""
            + "{"
            + "  \"fields\": ["
            + "    {"
            + "      \"name\": \"source\","
            + "      \"type\": \"STRING\","
            + "      \"mode\": \"NULLABLE\""
            + "    },"
            + "    {"
            + "      \"name\": \"quote\","
            + "      \"type\": \"STRING\","
            + "      \"mode\": \"REQUIRED\""
            + "    }"
            + "  ]"
            + "}";
    // [END BigQuerySchemaJson]

    {
      String tableSpec = "clouddataflow-readonly:samples.weather_stations";
      if (!writeProject.isEmpty() && !writeDataset.isEmpty() && !writeTable.isEmpty()) {
        tableSpec = writeProject + ":" + writeDataset + "." + writeTable;
      }

      // [START BigQuerySchemaObject]
      TableSchema tableSchema =
          new TableSchema()
              .setFields(
                  ImmutableList.of(
                      new TableFieldSchema()
                          .setName("source")
                          .setType("STRING")
                          .setMode("NULLABLE"),
                      new TableFieldSchema()
                          .setName("quote")
                          .setType("STRING")
                          .setMode("REQUIRED")));
      // [END BigQuerySchemaObject]

      // [START BigQueryWriteInput]
      /*
      @DefaultCoder(AvroCoder.class)
      static class Quote {
        final String source;
        final String quote;

        public Quote() {
          this.source = "";
          this.quote = "";
        }
        public Quote(String source, String quote) {
          this.source = source;
          this.quote = quote;
        }
      }
      */

      PCollection<Quote> quotes =
          p.apply(
              Create.of(
                  new Quote("Mahatma Gandhi", "My life is my message."),
                  new Quote("Yoda", "Do, or do not. There is no 'try'.")));
      // [END BigQueryWriteInput]

      // [START BigQueryWriteTable]
      quotes
          .apply(
              MapElements.into(TypeDescriptor.of(TableRow.class))
                  .via(
                      (Quote elem) ->
                          new TableRow().set("source", elem.source).set("quote", elem.quote)))
          .apply(
              BigQueryIO.writeTableRows()
                  .to(tableSpec)
                  .withSchema(tableSchema)
                  .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
                  .withWriteDisposition(WriteDisposition.WRITE_TRUNCATE));
      // [END BigQueryWriteTable]

      // [START BigQueryWriteFunction]
      quotes.apply(
          BigQueryIO.<Quote>write()
              .to(tableSpec)
              .withSchema(tableSchema)
              .withFormatFunction(
                  (Quote elem) ->
                      new TableRow().set("source", elem.source).set("quote", elem.quote))
              .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
              .withWriteDisposition(WriteDisposition.WRITE_TRUNCATE));
      // [END BigQueryWriteFunction]

      // [START BigQueryWriteJsonSchema]
      quotes.apply(
          BigQueryIO.<Quote>write()
              .to(tableSpec)
              .withJsonSchema(tableSchemaJson)
              .withFormatFunction(
                  (Quote elem) ->
                      new TableRow().set("source", elem.source).set("quote", elem.quote))
              .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
              .withWriteDisposition(WriteDisposition.WRITE_TRUNCATE));
      // [END BigQueryWriteJsonSchema]
    }

    {
      // [START BigQueryWriteDynamicDestinations]
      /*
      @DefaultCoder(AvroCoder.class)
      static class WeatherData {
        final long year;
        final long month;
        final long day;
        final double maxTemp;

        public WeatherData() {
          this.year = 0;
          this.month = 0;
          this.day = 0;
          this.maxTemp = 0.0f;
        }
        public WeatherData(long year, long month, long day, double maxTemp) {
          this.year = year;
          this.month = month;
          this.day = day;
          this.maxTemp = maxTemp;
        }
      }
      */

      PCollection<WeatherData> weatherData =
          p.apply(
              BigQueryIO.read(
                      (SchemaAndRecord elem) -> {
                        GenericRecord record = elem.getRecord();
                        return new WeatherData(
                            (Long) record.get("year"),
                            (Long) record.get("month"),
                            (Long) record.get("day"),
                            (Double) record.get("max_temperature"));
                      })
                  .fromQuery(
                      "SELECT year, month, day, max_temperature "
                          + "FROM [clouddataflow-readonly:samples.weather_stations] "
                          + "WHERE year BETWEEN 2007 AND 2009")
                  .withCoder(AvroCoder.of(WeatherData.class)));

      // We will send the weather data into different tables for every year.
      weatherData.apply(
          BigQueryIO.<WeatherData>write()
              .to(
                  new DynamicDestinations<WeatherData, Long>() {
                    @Override
                    public Long getDestination(ValueInSingleWindow<WeatherData> elem) {
                      return elem.getValue().year;
                    }

                    @Override
                    public TableDestination getTable(Long destination) {
                      return new TableDestination(
                          new TableReference()
                              .setProjectId(writeProject)
                              .setDatasetId(writeDataset)
                              .setTableId(writeTable + "_" + destination),
                          "Table for year " + destination);
                    }

                    @Override
                    public TableSchema getSchema(Long destination) {
                      return new TableSchema()
                          .setFields(
                              ImmutableList.of(
                                  new TableFieldSchema()
                                      .setName("year")
                                      .setType("INTEGER")
                                      .setMode("REQUIRED"),
                                  new TableFieldSchema()
                                      .setName("month")
                                      .setType("INTEGER")
                                      .setMode("REQUIRED"),
                                  new TableFieldSchema()
                                      .setName("day")
                                      .setType("INTEGER")
                                      .setMode("REQUIRED"),
                                  new TableFieldSchema()
                                      .setName("maxTemp")
                                      .setType("FLOAT")
                                      .setMode("NULLABLE")));
                    }
                  })
              .withFormatFunction(
                  (WeatherData elem) ->
                      new TableRow()
                          .set("year", elem.year)
                          .set("month", elem.month)
                          .set("day", elem.day)
                          .set("maxTemp", elem.maxTemp))
              .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
              .withWriteDisposition(WriteDisposition.WRITE_TRUNCATE));
      // [END BigQueryWriteDynamicDestinations]

      String tableSpec = "clouddataflow-readonly:samples.weather_stations";
      if (!writeProject.isEmpty() && !writeDataset.isEmpty() && !writeTable.isEmpty()) {
        tableSpec = writeProject + ":" + writeDataset + "." + writeTable + "_partitioning";
      }

      TableSchema tableSchema =
          new TableSchema()
              .setFields(
                  ImmutableList.of(
                      new TableFieldSchema().setName("year").setType("INTEGER").setMode("REQUIRED"),
                      new TableFieldSchema()
                          .setName("month")
                          .setType("INTEGER")
                          .setMode("REQUIRED"),
                      new TableFieldSchema().setName("day").setType("INTEGER").setMode("REQUIRED"),
                      new TableFieldSchema()
                          .setName("maxTemp")
                          .setType("FLOAT")
                          .setMode("NULLABLE")));

      // [START BigQueryTimePartitioning]
      weatherData.apply(
          BigQueryIO.<WeatherData>write()
              .to(tableSpec + "_partitioning")
              .withSchema(tableSchema)
              .withFormatFunction(
                  (WeatherData elem) ->
                      new TableRow()
                          .set("year", elem.year)
                          .set("month", elem.month)
                          .set("day", elem.day)
                          .set("maxTemp", elem.maxTemp))
              // NOTE: an existing table without time partitioning set up will not work
              .withTimePartitioning(new TimePartitioning().setType("DAY"))
              .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
              .withWriteDisposition(WriteDisposition.WRITE_TRUNCATE));
      // [END BigQueryTimePartitioning]
    }
  }

  /** Helper function to format results in coGroupByKeyTuple. */
  public static String formatCoGbkResults(
      String name, Iterable<String> emails, Iterable<String> phones) {

    List<String> emailsList = new ArrayList<>();
    for (String elem : emails) {
      emailsList.add("'" + elem + "'");
    }
    Collections.sort(emailsList);
    String emailsStr = "[" + String.join(", ", emailsList) + "]";

    List<String> phonesList = new ArrayList<>();
    for (String elem : phones) {
      phonesList.add("'" + elem + "'");
    }
    Collections.sort(phonesList);
    String phonesStr = "[" + String.join(", ", phonesList) + "]";

    return name + "; " + emailsStr + "; " + phonesStr;
  }

  /** Using a CoGroupByKey transform. */
  public static PCollection<String> coGroupByKeyTuple(
      TupleTag<String> emailsTag,
      TupleTag<String> phonesTag,
      PCollection<KV<String, String>> emails,
      PCollection<KV<String, String>> phones) {

    // [START CoGroupByKeyTuple]
    PCollection<KV<String, CoGbkResult>> results =
        KeyedPCollectionTuple.of(emailsTag, emails)
            .and(phonesTag, phones)
            .apply(CoGroupByKey.create());

    PCollection<String> contactLines =
        results.apply(
            ParDo.of(
                new DoFn<KV<String, CoGbkResult>, String>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    KV<String, CoGbkResult> e = c.element();
                    String name = e.getKey();
                    Iterable<String> emailsIter = e.getValue().getAll(emailsTag);
                    Iterable<String> phonesIter = e.getValue().getAll(phonesTag);
                    String formattedResult =
                        Snippets.formatCoGbkResults(name, emailsIter, phonesIter);
                    c.output(formattedResult);
                  }
                }));
    // [END CoGroupByKeyTuple]
    return contactLines;
  }

  public static void fileProcessPattern() throws Exception {
    Pipeline p = Pipeline.create();

    // [START FileProcessPatternProcessNewFilesSnip1]
    // This produces PCollection<MatchResult.Metadata>
    p.apply(
        FileIO.match()
            .filepattern("...")
            .continuously(
                Duration.standardSeconds(30),
                Watch.Growth.afterTimeSinceNewOutput(Duration.standardHours(1))));
    // [END FileProcessPatternProcessNewFilesSnip1]

    // [START FileProcessPatternProcessNewFilesSnip2]
    // This produces PCollection<String>
    p.apply(
        TextIO.read()
            .from("<path-to-files>/*")
            .watchForNewFiles(
                // Check for new files every minute.
                Duration.standardMinutes(1),
                // Stop watching the file pattern if no new files appear for an hour.
                Watch.Growth.afterTimeSinceNewOutput(Duration.standardHours(1))));
    // [END FileProcessPatternProcessNewFilesSnip2]

    // [START FileProcessPatternAccessMetadataSnip1]
    p.apply(FileIO.match().filepattern("hdfs://path/to/*.gz"))
        // The withCompression method is optional. By default, the Beam SDK detects compression from
        // the filename.
        .apply(FileIO.readMatches().withCompression(Compression.GZIP))
        .apply(
            ParDo.of(
                new DoFn<FileIO.ReadableFile, String>() {
                  @ProcessElement
                  public void process(@Element FileIO.ReadableFile file) {
                    // We can now access the file and its metadata.
                    LOG.info("File Metadata resourceId is {} ", file.getMetadata().resourceId());
                  }
                }));
    // [END FileProcessPatternAccessMetadataSnip1]

  }

  private static final Logger LOG = LoggerFactory.getLogger(Snippets.class);

  // [START SideInputPatternSlowUpdateGlobalWindowSnip1]

  public static void sideInputPatterns() {
    // This pipeline uses View.asSingleton for a placeholder external service.
    // Run in debug mode to see the output.
    Pipeline p = Pipeline.create();

    // Create a side input that updates each second.
    PCollectionView<Map<String, String>> map =
        p.apply(GenerateSequence.from(0).withRate(1, Duration.standardSeconds(5L)))
            .apply(Window.into(FixedWindows.of(Duration.standardSeconds(5))))
            .apply(Sum.longsGlobally().withoutDefaults())
            .apply(
                ParDo.of(
                    new DoFn<Long, Map<String, String>>() {

                      @ProcessElement
                      public void process(
                          @Element Long input,
                          @Timestamp Instant timestamp,
                          OutputReceiver<Map<String, String>> o) {
                        // Replace map with test data from the placeholder external service.
                        // Add external reads here.
                        o.output(PlaceholderExternalService.readTestData(timestamp));
                      }
                    }))
            .apply(
                Window.<Map<String, String>>into(new GlobalWindows())
                    .triggering(Repeatedly.forever(AfterProcessingTime.pastFirstElementInPane()))
                    .discardingFiredPanes())
            .apply(View.asSingleton());

    // Consume side input. GenerateSequence generates test data.
    // Use a real source (like PubSubIO or KafkaIO) in production.
    p.apply(GenerateSequence.from(0).withRate(1, Duration.standardSeconds(1L)))
        .apply(Window.into(FixedWindows.of(Duration.standardSeconds(1))))
        .apply(Sum.longsGlobally().withoutDefaults())
        .apply(
            ParDo.of(
                    new DoFn<Long, KV<Long, Long>>() {

                      @ProcessElement
                      public void process(ProcessContext c, @Timestamp Instant timestamp) {
                        Map<String, String> keyMap = c.sideInput(map);
                        c.outputWithTimestamp(KV.of(1L, c.element()), Instant.now());

                        LOG.info(
                            "Value is {} with timestamp {}, using key A from side input with time {}.",
                            c.element(),
                            timestamp.toString(DateTimeFormat.forPattern("HH:mm:ss")),
                            keyMap.get("Key_A"));
                      }
                    })
                .withSideInputs(map));

    p.run();
  }

  /** Placeholder class that represents an external service generating test data. */
  public static class PlaceholderExternalService {

    public static Map<String, String> readTestData(Instant timestamp) {

      Map<String, String> map = new HashMap<>();

      map.put("Key_A", timestamp.toString(DateTimeFormat.forPattern("HH:mm:ss")));

      return map;
    }
  }

  // [END SideInputPatternSlowUpdateGlobalWindowSnip1]

  // [START AccessingValueProviderInfoAfterRunSnip1]

  /** Sample of PipelineOptions with a ValueProvider option argument. */
  public interface MyOptions extends PipelineOptions {
    @Description("My option")
    @Default.String("Hello world!")
    ValueProvider<String> getStringValue();

    void setStringValue(ValueProvider<String> value);
  }

  public static void accessingValueProviderInfoAfterRunSnip1(String[] args) {

    MyOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(MyOptions.class);

    // Create pipeline.
    Pipeline p = Pipeline.create(options);

    // Add a branch for logging the ValueProvider value.
    p.apply(Create.of(1))
        .apply(
            ParDo.of(
                new DoFn<Integer, Integer>() {

                  // Define the DoFn that logs the ValueProvider value.
                  @ProcessElement
                  public void process(ProcessContext c) {

                    MyOptions ops = c.getPipelineOptions().as(MyOptions.class);
                    // This example logs the ValueProvider value, but you could store it by
                    // pushing it to an external database.

                    LOG.info("Option StringValue was {}", ops.getStringValue());
                  }
                }));

    // The main pipeline.
    p.apply(Create.of(1, 2, 3, 4)).apply(Sum.integersGlobally());

    p.run();
  }

  // [END AccessingValueProviderInfoAfterRunSnip1]

  private static final Duration gapDuration = Duration.standardSeconds(10L);

  // [START CustomSessionWindow1]

  public Collection<IntervalWindow> assignWindows(WindowFn.AssignContext c) {

    // Assign each element into a window from its timestamp until gapDuration in the
    // future.  Overlapping windows (representing elements within gapDuration of
    // each other) will be merged.
    return Arrays.asList(new IntervalWindow(c.timestamp(), gapDuration));
  }
  // [END CustomSessionWindow1]

  // [START CustomSessionWindow2]
  public static class DynamicSessions extends WindowFn<TableRow, IntervalWindow> {
    /** Duration of the gaps between sessions. */
    private final Duration gapDuration;

    /** Creates a {@code DynamicSessions} {@link WindowFn} with the specified gap duration. */
    private DynamicSessions(Duration gapDuration) {
      this.gapDuration = gapDuration;
    }

    // [END CustomSessionWindow2]

    // [START CustomSessionWindow3]
    @Override
    public Collection<IntervalWindow> assignWindows(AssignContext c) {
      // Assign each element into a window from its timestamp until gapDuration in the
      // future.  Overlapping windows (representing elements within gapDuration of
      // each other) will be merged.
      Duration dataDrivenGap;
      TableRow message = c.element();

      try {
        dataDrivenGap = Duration.standardSeconds(Long.parseLong(message.get("gap").toString()));
      } catch (Exception e) {
        dataDrivenGap = gapDuration;
      }
      return Arrays.asList(new IntervalWindow(c.timestamp(), dataDrivenGap));
    }
    // [END CustomSessionWindow3]

    // [START CustomSessionWindow4]
    /** Creates a {@code DynamicSessions} {@link WindowFn} with the specified gap duration. */
    public static DynamicSessions withDefaultGapDuration(Duration gapDuration) {
      return new DynamicSessions(gapDuration);
    }

    // [END CustomSessionWindow4]

    @Override
    public void mergeWindows(MergeContext c) throws Exception {}

    @Override
    public boolean isCompatible(WindowFn<?, ?> other) {
      return false;
    }

    @Override
    public Coder<IntervalWindow> windowCoder() {
      return null;
    }

    @Override
    public WindowMappingFn<IntervalWindow> getDefaultWindowMappingFn() {
      return null;
    }
  }

  public static class CustomSessionPipeline {

    public static void main(String[] args) {

      // [START CustomSessionWindow5]

      PCollection<TableRow> p =
          Pipeline.create()
              .apply(
                  "Create data",
                  Create.timestamped(
                      TimestampedValue.of(
                          new TableRow().set("user", "mobile").set("score", 12).set("gap", 5),
                          new Instant()),
                      TimestampedValue.of(
                          new TableRow().set("user", "desktop").set("score", 4), new Instant()),
                      TimestampedValue.of(
                          new TableRow().set("user", "mobile").set("score", -3).set("gap", 5),
                          new Instant().plus(Duration.millis(2000))),
                      TimestampedValue.of(
                          new TableRow().set("user", "mobile").set("score", 2).set("gap", 5),
                          new Instant().plus(Duration.millis(9000))),
                      TimestampedValue.of(
                          new TableRow().set("user", "mobile").set("score", 7).set("gap", 5),
                          new Instant().plus(Duration.millis(12000))),
                      TimestampedValue.of(
                          new TableRow().set("user", "desktop").set("score", 10),
                          new Instant().plus(Duration.millis(12000)))));
      // [END CustomSessionWindow5]

      // [START CustomSessionWindow6]
      p.apply(
          "Window into sessions",
          Window.<TableRow>into(
              DynamicSessions.withDefaultGapDuration(Duration.standardSeconds(10))));
      // [END CustomSessionWindow6]
    }
  }

  public static class DeadLetterBigQuery {

    public static void main(String[] args) {

      // [START BigQueryIODeadLetter]

      PipelineOptions options =
          PipelineOptionsFactory.fromArgs(args).withValidation().as(BigQueryOptions.class);

      Pipeline p = Pipeline.create(options);

      // Create a bug by writing the 2nd value as null. The API will correctly
      // throw an error when trying to insert a null value into a REQUIRED field.
      WriteResult result =
          p.apply(Create.of(1, 2))
              .apply(
                  BigQueryIO.<Integer>write()
                      .withSchema(
                          new TableSchema()
                              .setFields(
                                  ImmutableList.of(
                                      new TableFieldSchema()
                                          .setName("num")
                                          .setType("INTEGER")
                                          .setMode("REQUIRED"))))
                      .to("Test.dummyTable")
                      .withFormatFunction(x -> new TableRow().set("num", (x == 2) ? null : x))
                      .withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors())
                      // Forcing the bounded pipeline to use streaming inserts
                      .withMethod(BigQueryIO.Write.Method.STREAMING_INSERTS)
                      // set the withExtendedErrorInfo property.
                      .withExtendedErrorInfo()
                      .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
                      .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND));

      result
          .getFailedInsertsWithErr()
          .apply(
              MapElements.into(TypeDescriptors.strings())
                  .via(
                      x -> {
                        System.out.println(" The table was " + x.getTable());
                        System.out.println(" The row was " + x.getRow());
                        System.out.println(" The error was " + x.getError());
                        return "";
                      }));
      p.run();

      /*  Sample Output From the pipeline:
       <p>The table was GenericData{classInfo=[datasetId, projectId, tableId], {datasetId=Test,projectId=<>, tableId=dummyTable}}
       <p>The row was GenericData{classInfo=[f], {num=null}}
       <p>The error was GenericData{classInfo=[errors, index],{errors=[GenericData{classInfo=[debugInfo, location, message, reason], {debugInfo=,location=, message=Missing required field: Msg_0_CLOUD_QUERY_TABLE.num., reason=invalid}}],index=0}}
      */
    }
    // [END BigQueryIODeadLetter]
  }

  public static class PeriodicallyUpdatingSideInputs {

    public static PCollection<Long> main(
        Pipeline p,
        Instant startAt,
        Instant stopAt,
        Duration interval1,
        Duration interval2,
        String fileToRead) {
      // [START PeriodicallyUpdatingSideInputs]
      PCollectionView<List<Long>> sideInput =
          p.apply(
                  "SIImpulse",
                  PeriodicImpulse.create()
                      .startAt(startAt)
                      .stopAt(stopAt)
                      .withInterval(interval1)
                      .applyWindowing())
              .apply(
                  "FileToRead",
                  ParDo.of(
                      new DoFn<Instant, String>() {
                        @DoFn.ProcessElement
                        public void process(@Element Instant notUsed, OutputReceiver<String> o) {
                          o.output(fileToRead);
                        }
                      }))
              .apply(FileIO.matchAll())
              .apply(FileIO.readMatches())
              .apply(TextIO.readFiles())
              .apply(
                  ParDo.of(
                      new DoFn<String, String>() {
                        @ProcessElement
                        public void process(@Element String src, OutputReceiver<String> o) {
                          o.output(src);
                        }
                      }))
              .apply(Combine.globally(Count.<String>combineFn()).withoutDefaults())
              .apply(View.asList());

      PCollection<Instant> mainInput =
          p.apply(
              "MIImpulse",
              PeriodicImpulse.create()
                  .startAt(startAt.minus(Duration.standardSeconds(1)))
                  .stopAt(stopAt.minus(Duration.standardSeconds(1)))
                  .withInterval(interval2)
                  .applyWindowing());

      // Consume side input. GenerateSequence generates test data.
      // Use a real source (like PubSubIO or KafkaIO) in production.
      PCollection<Long> result =
          mainInput.apply(
              "generateOutput",
              ParDo.of(
                      new DoFn<Instant, Long>() {
                        @ProcessElement
                        public void process(ProcessContext c) {
                          c.output((long) c.sideInput(sideInput).size());
                        }
                      })
                  .withSideInputs(sideInput));
      // [END PeriodicallyUpdatingSideInputs]
      return result;
    }
  }

  public static class SchemaJoinPattern {
    public static PCollection<String> main(
        Pipeline p,
        final List<Row> emailUsers,
        final List<Row> phoneUsers,
        Schema emailSchema,
        Schema phoneSchema) {
      // [START SchemaJoinPatternJoin]
      // Create/Read Schema PCollections
      PCollection<Row> emailList =
          p.apply("CreateEmails", Create.of(emailUsers).withRowSchema(emailSchema));

      PCollection<Row> phoneList =
          p.apply("CreatePhones", Create.of(phoneUsers).withRowSchema(phoneSchema));

      // Perform Join
      PCollection<Row> resultRow =
          emailList.apply("Apply Join", Join.<Row, Row>innerJoin(phoneList).using("name"));

      // Preview Result
      resultRow.apply(
          "Preview Result",
          MapElements.into(TypeDescriptors.strings())
              .via(
                  x -> {
                    System.out.println(x);
                    return "";
                  }));

      /* Sample Output From the pipeline:
       Row:[Row:[person1, person1@example.com], Row:[person1, 111-222-3333]]
       Row:[Row:[person2, person2@example.com], Row:[person2, 222-333-4444]]
       Row:[Row:[person4, person4@example.com], Row:[person4, 555-333-4444]]
       Row:[Row:[person3, person3@example.com], Row:[person3, 444-333-4444]]
      */
      // [END SchemaJoinPatternJoin]

      // [START SchemaJoinPatternFormat]
      PCollection<String> result =
          resultRow.apply(
              "Format Output",
              MapElements.into(TypeDescriptors.strings())
                  .via(
                      x -> {
                        String userInfo =
                            "Name: "
                                + x.getRow(0).getValue("name")
                                + " Email: "
                                + x.getRow(0).getValue("email")
                                + " Phone: "
                                + x.getRow(1).getValue("phone");
                        System.out.println(userInfo);
                        return userInfo;
                      }));

      /* Sample output From the pipeline
      Name: person4 Email: person4@example.com Phone: 555-333-4444
      Name: person2 Email: person2@example.com Phone: 222-333-4444
      Name: person3 Email: person3@example.com Phone: 444-333-4444
      Name: person1 Email: person1@example.com Phone: 111-222-3333
       */
      // [END SchemaJoinPatternFormat]

      return result;
    }
  }

  public static class NaturalLanguageIntegration {
    private static final SerializableFunction<AnnotateTextResponse, List<Map<String, List<String>>>>
        // [START NlpAnalyzeDependencyTree]
        analyzeDependencyTree =
            (SerializableFunction<AnnotateTextResponse, List<Map<String, List<String>>>>)
                response -> {
                  List<Map<String, List<String>>> adjacencyLists = new ArrayList<>();
                  int index = 0;
                  for (Sentence s : response.getSentencesList()) {
                    Map<String, List<String>> adjacencyMap = new HashMap<>();
                    int sentenceBegin = s.getText().getBeginOffset();
                    int sentenceEnd = sentenceBegin + s.getText().getContent().length() - 1;
                    while (index < response.getTokensCount()
                        && response.getTokens(index).getText().getBeginOffset() <= sentenceEnd) {
                      Token token = response.getTokensList().get(index);
                      int headTokenIndex = token.getDependencyEdge().getHeadTokenIndex();
                      String headTokenContent =
                          response.getTokens(headTokenIndex).getText().getContent();
                      List<String> adjacencyList =
                          adjacencyMap.getOrDefault(headTokenContent, new ArrayList<>());
                      adjacencyList.add(token.getText().getContent());
                      adjacencyMap.put(headTokenContent, adjacencyList);
                      index++;
                    }
                    adjacencyLists.add(adjacencyMap);
                  }
                  return adjacencyLists;
                };
    // [END NlpAnalyzeDependencyTree]

    private static final SerializableFunction<? super AnnotateTextResponse, TextSentiments>
        // [START NlpExtractSentiments]
        extractSentiments =
        (SerializableFunction<AnnotateTextResponse, TextSentiments>)
            annotateTextResponse -> {
              TextSentiments sentiments = new TextSentiments();
              sentiments.setDocumentSentiment(
                  annotateTextResponse.getDocumentSentiment().getMagnitude());
              Map<String, Float> sentenceSentimentsMap =
                  annotateTextResponse.getSentencesList().stream()
                      .collect(
                          Collectors.toMap(
                              (Sentence s) -> s.getText().getContent(),
                              (Sentence s) -> s.getSentiment().getMagnitude()));
              sentiments.setSentenceSentiments(sentenceSentimentsMap);
              return sentiments;
            };
    // [END NlpExtractSentiments]

    private static final SerializableFunction<? super AnnotateTextResponse, Map<String, String>>
        // [START NlpExtractEntities]
        extractEntities =
        (SerializableFunction<AnnotateTextResponse, Map<String, String>>)
            annotateTextResponse ->
                annotateTextResponse.getEntitiesList().stream()
                    .collect(
                        Collectors.toMap(Entity::getName, (Entity e) -> e.getType().toString()));
    // [END NlpExtractEntities]

    private static final SerializableFunction<? super Map<String, String>, String>
        mapEntitiesToJson =
            (SerializableFunction<Map<String, String>, String>)
                item -> {
                  StringBuilder builder = new StringBuilder("[");
                  builder.append(
                      item.entrySet().stream()
                          .map(
                              entry -> "{\"" + entry.getKey() + "\": \"" + entry.getValue() + "\"}")
                          .collect(Collectors.joining(",")));
                  builder.append("]");
                  return builder.toString();
                };

    private static final SerializableFunction<List<Map<String, List<String>>>, String>
        mapDependencyTreesToJson =
            (SerializableFunction<List<Map<String, List<String>>>, String>)
                tree -> {
                  Gson gson = new Gson();
                  return gson.toJson(tree);
                };

    public static void main(Pipeline p) {
      // [START NlpAnalyzeText]
      AnnotateTextRequest.Features features =
          AnnotateTextRequest.Features.newBuilder()
              .setExtractEntities(true)
              .setExtractDocumentSentiment(true)
              .setExtractEntitySentiment(true)
              .setExtractSyntax(true)
              .build();
      AnnotateText annotateText = AnnotateText.newBuilder().setFeatures(features).build();

      PCollection<AnnotateTextResponse> responses =
          p.apply(
                  Create.of(
                      "My experience so far has been fantastic, "
                          + "I\'d really recommend this product."))
              .apply(
                  MapElements.into(TypeDescriptor.of(Document.class))
                      .via(
                          (SerializableFunction<String, Document>)
                              input ->
                                  Document.newBuilder()
                                      .setContent(input)
                                      .setType(Document.Type.PLAIN_TEXT)
                                      .build()))
              .apply(annotateText);

      responses
          .apply(MapElements.into(TypeDescriptor.of(TextSentiments.class)).via(extractSentiments))
          .apply(
              MapElements.into(TypeDescriptors.strings())
                  .via((SerializableFunction<TextSentiments, String>) TextSentiments::toJson))
          .apply(TextIO.write().to("sentiments.txt"));

      responses
          .apply(
              MapElements.into(
                      TypeDescriptors.maps(TypeDescriptors.strings(), TypeDescriptors.strings()))
                  .via(extractEntities))
          .apply(MapElements.into(TypeDescriptors.strings()).via(mapEntitiesToJson))
          .apply(TextIO.write().to("entities.txt"));

      responses
          .apply(
              MapElements.into(
                      TypeDescriptors.lists(
                          TypeDescriptors.maps(
                              TypeDescriptors.strings(),
                              TypeDescriptors.lists(TypeDescriptors.strings()))))
                  .via(analyzeDependencyTree))
          .apply(MapElements.into(TypeDescriptors.strings()).via(mapDependencyTreesToJson))
          .apply(TextIO.write().to("adjacency_list.txt"));
      // [END NlpAnalyzeText]
    }

    private static class TextSentiments implements Serializable {
      private Float documentSentiment;
      private Map<String, Float> sentenceSentiments;

      public void setSentenceSentiments(Map<String, Float> sentenceSentiments) {
        this.sentenceSentiments = sentenceSentiments;
      }

      public Float getDocumentSentiment() {
        return documentSentiment;
      }

      public void setDocumentSentiment(Float documentSentiment) {
        this.documentSentiment = documentSentiment;
      }

      public Map<String, Float> getSentenceSentiments() {
        return sentenceSentiments;
      }

      public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
      }
    }
  }

  @SuppressWarnings("unused")
  private static class BundleFinalization {
    private static class BundleFinalizationDoFn extends DoFn<String, Integer> {
      // [START BundleFinalize]
      @ProcessElement
      public void processElement(ProcessContext c, BundleFinalizer bundleFinalizer) {
        // ... produce output ...

        bundleFinalizer.afterBundleCommit(
            Instant.now().plus(Duration.standardMinutes(5)),
            () -> {
              // ... perform a side effect ...
            });
      }
      // [END BundleFinalize]
    }
  }

  @SuppressWarnings("unused")
  private static class SplittableDoFn {

    private static void seekToNextRecordBoundaryInFile(
        RandomAccessFile file, long initialPosition) {}

    private static Integer readNextRecord(RandomAccessFile file) {
      // ... read a record ...
      return null;
    }

    // [START SDF_BasicExample]
    @BoundedPerElement
    private static class FileToWordsFn extends DoFn<String, Integer> {
      @GetInitialRestriction
      public OffsetRange getInitialRestriction(@Element String fileName) throws IOException {
        return new OffsetRange(0, new File(fileName).length());
      }

      @ProcessElement
      public void processElement(
          @Element String fileName,
          RestrictionTracker<OffsetRange, Long> tracker,
          OutputReceiver<Integer> outputReceiver)
          throws IOException {
        RandomAccessFile file = new RandomAccessFile(fileName, "r");
        seekToNextRecordBoundaryInFile(file, tracker.currentRestriction().getFrom());
        while (tracker.tryClaim(file.getFilePointer())) {
          outputReceiver.output(readNextRecord(file));
        }
      }

      // Providing the coder is only necessary if it can not be inferred at runtime.
      @GetRestrictionCoder
      public Coder<OffsetRange> getRestrictionCoder() {
        return OffsetRange.Coder.of();
      }
    }
    // [END SDF_BasicExample]

    @SuppressWarnings("unused")
    private static class BasicExampleWithInitialSplitting extends FileToWordsFn {
      // [START SDF_BasicExampleWithSplitting]
      void splitRestriction(
          @Restriction OffsetRange restriction, OutputReceiver<OffsetRange> splitReceiver) {
        long splitSize = 64 * (1 << 20);
        long i = restriction.getFrom();
        while (i < restriction.getTo() - splitSize) {
          // Compute and output 64 MiB size ranges to process in parallel
          long end = i + splitSize;
          splitReceiver.output(new OffsetRange(i, end));
          i = end;
        }
        // Output the last range
        splitReceiver.output(new OffsetRange(i, restriction.getTo()));
      }
      // [END SDF_BasicExampleWithSplitting]
    }

    @SuppressWarnings("unused")
    private static class BasicExampleWithBadTryClaimLoop extends DoFn<String, Integer> {
      // [START SDF_BadTryClaimLoop]
      @ProcessElement
      public void badTryClaimLoop(
          @Element String fileName,
          RestrictionTracker<OffsetRange, Long> tracker,
          OutputReceiver<Integer> outputReceiver)
          throws IOException {
        RandomAccessFile file = new RandomAccessFile(fileName, "r");
        seekToNextRecordBoundaryInFile(file, tracker.currentRestriction().getFrom());
        // The restriction tracker can be modified by another thread in parallel
        // so storing state locally is ill advised.
        long end = tracker.currentRestriction().getTo();
        while (file.getFilePointer() < end) {
          // Only after successfully claiming should we produce any output and/or
          // perform side effects.
          tracker.tryClaim(file.getFilePointer());
          outputReceiver.output(readNextRecord(file));
        }
      }
      // [END SDF_BadTryClaimLoop]
    }

    @SuppressWarnings("unused")
    private static class CustomWatermarkEstimatorExample extends DoFn<String, Integer> {
      private static Instant currentWatermark = Instant.now();

      // [START SDF_CustomWatermarkEstimator]

      // (Optional) Define a custom watermark state type to save information between bundle
      // processing rounds.
      public static class MyCustomWatermarkState {
        public MyCustomWatermarkState(String element, OffsetRange restriction) {
          // Store data necessary for future watermark computations
        }
      }

      // (Optional) Choose which coder to use to encode the watermark estimator state.
      @GetWatermarkEstimatorStateCoder
      public Coder<MyCustomWatermarkState> getWatermarkEstimatorStateCoder() {
        return AvroCoder.of(MyCustomWatermarkState.class);
      }

      // Define a WatermarkEstimator
      public static class MyCustomWatermarkEstimator
          implements TimestampObservingWatermarkEstimator<MyCustomWatermarkState> {

        public MyCustomWatermarkEstimator(MyCustomWatermarkState type) {
          // Initialize watermark estimator state
        }

        @Override
        public void observeTimestamp(Instant timestamp) {
          // Will be invoked on each output from the SDF
        }

        @Override
        public Instant currentWatermark() {
          // Return a monotonically increasing value
          return currentWatermark;
        }

        @Override
        public MyCustomWatermarkState getState() {
          // Return state to resume future watermark estimation after a checkpoint/split
          return null;
        }
      }

      // Then, update the DoFn to generate the initial watermark estimator state for all new element
      // and restriction pairs and to create a new instance given watermark estimator state.

      @GetInitialWatermarkEstimatorState
      public MyCustomWatermarkState getInitialWatermarkEstimatorState(
          @Element String element, @Restriction OffsetRange restriction) {
        // Compute and return the initial watermark estimator state for each element and
        // restriction. All subsequent processing of an element and restriction will be restored
        // from the existing state.
        return new MyCustomWatermarkState(element, restriction);
      }

      @NewWatermarkEstimator
      public WatermarkEstimator<MyCustomWatermarkState> newWatermarkEstimator(
          @WatermarkEstimatorState MyCustomWatermarkState oldState) {
        return new MyCustomWatermarkEstimator(oldState);
      }
    }
    // [END SDF_CustomWatermarkEstimator]

    @SuppressWarnings("unused")
    private static class UserInitiatedCheckpointExample extends DoFn<String, Integer> {
      public static class ThrottlingException extends Exception {}

      public static class ElementNotReadyException extends Exception {}

      private Service initializeService() {
        return null;
      }

      public interface Service {
        List<Record> readNextRecords(long position) throws ThrottlingException;
      }

      public interface Record {
        long getPosition();
      }

      // [START SDF_UserInitiatedCheckpoint]
      @ProcessElement
      public ProcessContinuation processElement(
          RestrictionTracker<OffsetRange, Long> tracker, OutputReceiver<Record> outputReceiver) {
        long currentPosition = tracker.currentRestriction().getFrom();
        Service service = initializeService();
        try {
          while (true) {
            List<Record> records = service.readNextRecords(currentPosition);
            if (records.isEmpty()) {
              // Return a short delay if there is no data to process at the moment.
              return ProcessContinuation.resume().withResumeDelay(Duration.standardSeconds(10));
            }
            for (Record record : records) {
              if (!tracker.tryClaim(record.getPosition())) {
                return ProcessContinuation.stop();
              }
              currentPosition = record.getPosition() + 1;

              outputReceiver.output(record);
            }
          }
        } catch (ThrottlingException exception) {
          // Return a longer delay in case we are being throttled.
          return ProcessContinuation.resume().withResumeDelay(Duration.standardSeconds(60));
        }
      }
      // [END SDF_UserInitiatedCheckpoint]
    }

    private static class TruncateExample extends DoFn<String, Integer> {
      // [START SDF_Truncate]
      @TruncateRestriction
      @Nullable
      TruncateResult<OffsetRange> truncateRestriction(
          @Element String fileName, @Restriction OffsetRange restriction) {
        if (fileName.contains("optional")) {
          // Skip optional files
          return null;
        }
        return TruncateResult.of(restriction);
      }
      // [END SDF_Truncate]
    }

    @SuppressWarnings("unused")
    private static class GetSizeExample extends DoFn<String, Integer> {
      // [START SDF_GetSize]
      @GetSize
      double getSize(@Element String fileName, @Restriction OffsetRange restriction) {
        return (fileName.contains("expensiveRecords") ? 2 : 1) * restriction.getTo()
            - restriction.getFrom();
      }
      // [END SDF_GetSize]
    }
  }
}
