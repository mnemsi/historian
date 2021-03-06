package com.hurence.historian


import com.hurence.historian.LoaderMode.LoaderMode
import com.hurence.logisland.processor.StandardProcessContext
import com.hurence.logisland.record.{FieldDictionary, Record, RecordDictionary, StandardRecord, TimeSeriesRecord}
import com.hurence.logisland.timeseries.converter.compaction.BinaryCompactionConverter
import org.apache.commons.cli.{CommandLine, GnuParser, Option, OptionBuilder, Options, Parser}
import org.apache.spark.sql.{Encoder, Encoders, Row, SaveMode, SparkSession}
import org.apache.spark.sql.functions._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConversions._

case class EvoaMeasure(name: String,
                       codeInstall: String,
                       sensor: String,
                       value: Double,
                       quality: Double,
                       timestamp: String,
                       timeMs: Long,
                       year: Int,
                       month: Int,
                       week: Int,
                       day: Int,
                       filePath: String)


object LoaderMode extends Enumeration {
  type LoaderMode = Value

  val PRELOAD = Value("preload")
  val CHUNK = Value("chunk")
  val CHUNK_BY_FILE = Value("chunk_by_file")
  val TAG_CHUNK = Value("tag_chunk")
}

case class LoaderOptions(mode: LoaderMode, in: String, out: String, master: String, appName: String, brokers: scala.Option[String], lookup: scala.Option[String], chunkSize: Int, saxAlphabetSize: Int, saxStringLength: Int, useKerberos: Boolean)


/**
  * @author Thomas Bailet @Hurence
  */
object App {

  private val logger = LoggerFactory.getLogger(classOf[App])

  val DEFAULT_CHUNK_SIZE = 2000
  val DEFAULT_SAX_ALPHABET_SIZE = 7
  val DEFAULT_SAX_STRING_LENGTH = 100

  def parseCommandLine(args: Array[String]): LoaderOptions = {
    // Commande lien management
    val parser = new GnuParser
    val options = new Options


    val helpMsg = "Print this message."
    val help = new Option("help", helpMsg)
    options.addOption(help)

    // Mode
    OptionBuilder.withArgName("mode")
    OptionBuilder.withLongOpt("loading-mode")
    OptionBuilder.isRequired
    OptionBuilder.hasArg
    OptionBuilder.withDescription("preload or chunk : preload takes local csv files from input-path and partition data into year/month/code_install/name output-path whereas chunk takes hdfs input-path preloaded data and chunk it to kafka")
    val mode = OptionBuilder.create("mode")
    options.addOption(mode)

    // Input Path
    OptionBuilder.withArgName("in")
    OptionBuilder.withLongOpt("input-path")
    OptionBuilder.isRequired
    OptionBuilder.hasArg
    OptionBuilder.withDescription("input local folder for preload mode and input hdfs folder for chunk mode")
    val in = OptionBuilder.create("in")
    options.addOption(in)

    // Output Path
    OptionBuilder.withArgName("out")
    OptionBuilder.withLongOpt("output-path")
    OptionBuilder.isRequired
    OptionBuilder.hasArg
    OptionBuilder.withDescription("output local folder for preload mode and output kafka topic for chunk mode")
    val out = OptionBuilder.create("out")
    options.addOption(out)

    // Kafka brokers
    OptionBuilder.withArgName("brokers")
    OptionBuilder.withLongOpt("kafka-brokers")
    OptionBuilder.hasArg
    OptionBuilder.withDescription("kafka brokers for chunk mode")
    val brokers = OptionBuilder.create("brokers")
    options.addOption(brokers)

    // Spark master
    OptionBuilder.withArgName("master")
    OptionBuilder.withLongOpt("spark-master")
    OptionBuilder.hasArg
    OptionBuilder.isRequired
    OptionBuilder.withDescription("spark master")
    val master = OptionBuilder.create("master")
    options.addOption(master)

    // Lookup path
    OptionBuilder.withArgName("lookup")
    OptionBuilder.withLongOpt("lookup-path")
    OptionBuilder.hasArg
    OptionBuilder.withDescription("csv lookup ath for join")
    val lookup = OptionBuilder.create("lookup")
    options.addOption(lookup)

    // Chunk size
    OptionBuilder.withArgName("chunks")
    OptionBuilder.withLongOpt("chunks-size")
    OptionBuilder.hasArg
    OptionBuilder.withDescription("num points in a chunk, default 2000")
    val chunk = OptionBuilder.create("chunks")
    options.addOption(chunk)

    // SAX Alphabet size
    OptionBuilder.withArgName("sa")
    OptionBuilder.withLongOpt("sax-alphabet-size")
    OptionBuilder.hasArg
    OptionBuilder.withDescription("size of alphabet, default 7")
    val sa = OptionBuilder.create("sa")
    options.addOption(sa)

    // SAX String length
    OptionBuilder.withArgName("sl")
    OptionBuilder.withLongOpt("sax-length")
    OptionBuilder.hasArg
    OptionBuilder.withDescription("num points in a chunk, default 100")
    val sl = OptionBuilder.create("sl")
    options.addOption(sl)

    // SAX String length
    OptionBuilder.withArgName("kb")
    OptionBuilder.withLongOpt("kerberos")
    OptionBuilder.withDescription("do we use kerberos ?")
    val kb = OptionBuilder.create("kb")
    options.addOption(kb)

    // parse the command line arguments
    val line = parser.parse(options, args)
    val loadingMode = LoaderMode.withName(line.getOptionValue("mode").toLowerCase)
    val inputPath = line.getOptionValue("in")
    val outputPath = line.getOptionValue("out")
    val sparkMaster = line.getOptionValue("master")
    val useKerberos = if (line.hasOption("kb")) true else false
    val lookupPath = if (line.hasOption("lookup")) Some(line.getOptionValue("lookup")) else None
    val kafkaBrokers = if (line.hasOption("brokers")) Some(line.getOptionValue("brokers")) else None
    val chunksSize = if (line.hasOption("chunks")) line.getOptionValue("chunks").toInt else DEFAULT_CHUNK_SIZE
    val alphabetSize = if (line.hasOption("sa")) line.getOptionValue("sa").toInt else DEFAULT_SAX_ALPHABET_SIZE
    val saxStringLength = if (line.hasOption("sl")) line.getOptionValue("sl").toInt else DEFAULT_SAX_STRING_LENGTH


    if (loadingMode == LoaderMode.CHUNK && kafkaBrokers.isEmpty)
      throw new IllegalArgumentException(s"kafka broker must not be empty with $loadingMode mode")

    val appName = loadingMode match {
      case LoaderMode.PRELOAD => "EvoaPreloader"
      case LoaderMode.CHUNK => "EvoaChunker"
      case LoaderMode.CHUNK_BY_FILE => "EvoaChunkerByFile"
      case LoaderMode.TAG_CHUNK => "TagChunk"
      case _ => throw new IllegalArgumentException(s"unknown $loadingMode mode")
    }

    LoaderOptions(loadingMode, inputPath, outputPath, sparkMaster, appName, kafkaBrokers, lookupPath, chunksSize, alphabetSize, saxStringLength, useKerberos)


  }


  def preload(options: LoaderOptions, spark: SparkSession): Unit = {

    import spark.implicits._

    // Define formats
    val csvRegexp = "((\\w+)\\.?(\\w+-?\\w+-?\\w+)?\\.?(\\w+)?)"
    val dateFmt = "dd/MM/yyyy HH:mm:ss"

    // Load raw data
    val rawMeasuresDF = spark.read.format("csv")
      .option("sep", ";")
      .option("inferSchema", "true")
      .option("header", "true")
      .load(options.in)

    // Then sort and split columns
    val measuresDF = if (options.lookup.isDefined) {
      val lookupDF = spark.read.format("csv")
        .option("sep", ";")
        .option("inferSchema", "true")
        .option("header", "true")
        .option("encoding", "ISO-8859-1")
        .load(options.lookup.get)

      rawMeasuresDF.join(lookupDF, "tagname")
        .withColumn("time_ms", unix_timestamp($"timestamp", dateFmt) * 1000)
        .withColumn("year", year(to_date($"timestamp", dateFmt)))
        .withColumn("month", month(to_date($"timestamp", dateFmt)))
        .withColumn("day", dayofmonth(to_date($"timestamp", dateFmt)))
        .withColumn("name", regexp_extract($"tagname", csvRegexp, 1))
        .withColumn("code_install", regexp_extract($"tagname", csvRegexp, 2))
        .withColumn("sensor", regexp_extract($"tagname", csvRegexp, 3))
        .withColumn("numeric_type", regexp_extract($"tagname", csvRegexp, 4))
        .select("name", "value", "quality", "code_install", "sensor", "numeric_type", "description", "engunits", "timestamp", "time_ms", "year", "month", "day")
        .sort(asc("name"), asc("time_ms"))
      // .dropDuplicates()
    } else {

      rawMeasuresDF
        .withColumn("time_ms", unix_timestamp($"timestamp", dateFmt) * 1000)
        .withColumn("year", year(to_date($"timestamp", dateFmt)))
        .withColumn("month", month(to_date($"timestamp", dateFmt)))
        .withColumn("day", dayofmonth(to_date($"timestamp", dateFmt)))
        .withColumn("week", weekofyear(to_date($"timestamp", dateFmt)))
        .withColumn("name", regexp_extract($"tagname", csvRegexp, 1))
        .withColumn("code_install", regexp_extract($"tagname", csvRegexp, 2))
        .withColumn("sensor", regexp_extract($"tagname", csvRegexp, 3))
        .withColumn("numeric_type", regexp_extract($"tagname", csvRegexp, 4))
        .select("name", "value", "quality", "code_install", "sensor", "timestamp", "time_ms", "year", "month", "week", "day")
        //  .dropDuplicates()
        .orderBy(asc("name"), asc("time_ms"))
    }

    // save this to output path
    measuresDF
      .write
      .mode(SaveMode.Append)
      .partitionBy("year", "month", "week", "code_install", "name")
      .format("csv")
      .option("header", "true")
      .save(options.out)


    println("Preloading done")
  }

  def chunk(options: LoaderOptions, spark: SparkSession): Unit = {

    import spark.implicits._

    val testDF = spark.read.format("csv")
      .option("sep", ",")
      .option("inferSchema", "true")
      .option("header", "true")
      .load(options.in)
      .cache()


    val n = testDF.agg(countDistinct($"name")).collect().head.getLong(0).toInt


    implicit val enc: Encoder[TimeSeriesRecord] = org.apache.spark.sql.Encoders.kryo[TimeSeriesRecord]

    val tsDF = testDF.repartition(n, $"name")
      .sortWithinPartitions($"name", $"time_ms")
      .mapPartitions(partition => {

        // Init the Timeserie processor
        val tsProcessor = new TimeseriesConverter()
        val context = new StandardProcessContext(tsProcessor, "")
        context.setProperty(TimeseriesConverter.GROUPBY.getName, "name")
        context.setProperty(TimeseriesConverter.METRIC.getName,
          s"min;max;avg;trend;outlier;sax:${options.saxAlphabetSize},0.01,${options.saxStringLength}")
        tsProcessor.init(context)

        // Slide over each group
        partition.toList
          .groupBy(row => row.getString(row.fieldIndex("name")))
          .flatMap(group => group._2
            .sliding(options.chunkSize, options.chunkSize)
            .map(subGroup => tsProcessor.toTimeseriesRecord(group._1, subGroup.toList))
            .map(ts => (ts.getId, tsProcessor.serialize(ts)))
          )
          .iterator
      }).toDF("key", "value")


    // Write this chunk dataframe to Kafka
    if (options.useKerberos) {
      tsDF
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
        .write
        .format("kafka")
        .option("kafka.bootstrap.servers", options.brokers.get)
        .option("kafka.security.protocol", "SASL_PLAINTEXT")
        .option("kafka.sasl.kerberos.service.name", "kafka")
        .option("topic", options.out)
        .save()
    } else {
      tsDF
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
        .write
        .format("kafka")
        .option("kafka.bootstrap.servers", options.brokers.get)
        .option("topic", options.out)
        .save()
    }


  }


  def chunkByFile(options: LoaderOptions, spark: SparkSession): Unit = {

    import spark.implicits._


    val pattern = "(.+):((.+)\\/year=(.+)\\/month=(.+)\\/week=(.+)\\/code_install=(.+)\\/name=(.+)\\/(.+))".r
    val tsDF = spark.sparkContext.wholeTextFiles(options.in).map(r => {


      try {
        val filePath = r._1
        val pathTokens = pattern.findAllIn(filePath).matchData.toList.head

        logger.info(s"processing $filePath")

        val year = pathTokens.group(4).toInt
        val month = pathTokens.group(5).toInt
        val week = pathTokens.group(6).toInt
        val codeInstall = pathTokens.group(7)
        val name = pathTokens.group(8)

        val measures = r._2
          .split("\n")
          .tail
          .map(line => {
            try {
              val lineTokens = line.split(",")
              val value = lineTokens(0).toDouble
              val quality = lineTokens(1).toDouble
              val sensor = lineTokens(2)
              val timestamp = lineTokens(3)
              val timeMs = lineTokens(4).toLong
              val day = lineTokens(5).toInt

              Some(EvoaMeasure(name, codeInstall, sensor, value, quality, timestamp, timeMs, year, month, week, day, filePath))
            } catch {
              case _: Throwable => None
            }

          })
          .filter(_.isDefined)
          .map(_.get)
          .sortBy(_.timeMs)

        Some((name, measures))
      } catch {
        case _: Throwable => None
      }


    })
      .filter(_.isDefined)
      .map(_.get)
      .flatMap(m => {

        val name = m._1
        val measures = m._2

        // Init the Timeserie processor
        val tsProcessor = new TimeseriesConverter()
        val context = new StandardProcessContext(tsProcessor, "")
        context.setProperty(TimeseriesConverter.GROUPBY.getName, "name")
        context.setProperty(TimeseriesConverter.METRIC.getName,
          s"first;sum;min;max;avg;trend;outlier;sax:${options.saxAlphabetSize},0.005,${options.saxStringLength}")
        tsProcessor.init(context)

        // Slide over each group
        measures.sliding(options.chunkSize, options.chunkSize)
          .map(subGroup => tsProcessor.fromMeasurestoTimeseriesRecord(subGroup.toList))
          .map(ts => (ts.getId, tsProcessor.serialize(ts)))
      })
      .toDF("key", "value")


    // Write this chunk dataframe to Kafka
    if (options.useKerberos) {
      tsDF
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
        .write
        .format("kafka")
        .option("kafka.bootstrap.servers", options.brokers.get)
        .option("kafka.security.protocol", "SASL_PLAINTEXT")
        .option("kafka.sasl.kerberos.service.name", "kafka")
        .option("topic", options.out)
        .save()
    } else {
      tsDF
        .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
        .write
        .format("kafka")
        .option("kafka.bootstrap.servers", options.brokers.get)
        .option("topic", options.out)
        .save()
    }


  }


  /**
    *
    * @param args
    */
  def main(args: Array[String]): Unit = {

    // get arguments
    val options = parseCommandLine(args)

    // setup spark session
    val spark = SparkSession.builder
      .appName(options.appName)
      .master(options.master)
      .getOrCreate()

    // process
    options.mode match {
      case LoaderMode.PRELOAD => preload(options, spark)
      case LoaderMode.CHUNK => chunk(options, spark)
      case LoaderMode.CHUNK_BY_FILE => chunkByFile(options, spark)
      case LoaderMode.TAG_CHUNK =>
        val tagger = new ChunkTagger()
        tagger.process(options, spark)
      case _ => println(s"unknown loader mode : $LoaderMode")
    }
  }

}
