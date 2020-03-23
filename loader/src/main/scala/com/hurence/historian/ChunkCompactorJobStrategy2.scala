package com.hurence.historian

import java.util

import com.hurence.historian.modele.{CompactorJobReport, JobStatus}
import com.hurence.logisland.record.{EvoaUtils, TimeSeriesRecord}
import com.hurence.logisland.timeseries.MetricTimeSeries
import com.hurence.solr.SparkSolrUtils
import com.lucidworks.spark.util.{HurenceSolrSupport, SolrSupport}
import org.apache.solr.client.solrj.response.UpdateResponse
import org.apache.solr.common.SolrInputDocument
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{col, sum}
import org.apache.spark.sql.{DataFrame, Dataset, Encoders, Row, SparkSession, functions => f}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable.{WrappedArray => ArrayDF}

class ChunkCompactorJobStrategy2(options: ChunkCompactorConfStrategy2) extends ChunkCompactor {

  private val logger = LoggerFactory.getLogger(classOf[ChunkCompactorJobStrategy2])
  private val totalNumberOfPointColumnName = "number_points_day"
  private val startsColumnName = "starts"
  private val valuesColumnName = "values"
  private val endsColumnName = "ends"
  private val sizesColumnName = "sizes"
  private val nameColumnName = "name"

  private implicit val tsrEncoder = org.apache.spark.sql.Encoders.kryo[TimeSeriesRecord]

  private val jobStart: Long = System.currentTimeMillis()
  private val jobId: String = buildJobId(jobStart)
  private val commitWithinMs = -1


  private def loadChunksToBeTaggegFromSolR(spark: SparkSession): DataFrame = {
    val fields = s"${TimeSeriesRecord.CHUNK_ID}"

    val solrOpts = Map(
      "zkhost" -> options.zkHosts,
      "collection" -> options.timeseriesCollectionName,
      "fields" -> fields,
      "filters" -> options.solrFq
    )
    logger.info("solrOpts : {}", solrOpts.mkString("\n{", "\n", "}"))
    SparkSolrUtils.loadFromSolR(spark, solrOpts)
  }

  private def loadTaggedChunksFromSolR(spark: SparkSession): DataFrame = {
    val fields = s"${TimeSeriesRecord.CHUNK_ID},${TimeSeriesRecord.METRIC_NAME},${TimeSeriesRecord.CHUNK_VALUE},${TimeSeriesRecord.CHUNK_START}," +
      s"${TimeSeriesRecord.CHUNK_END},${TimeSeriesRecord.CHUNK_SIZE},${TimeSeriesRecord.CHUNK_YEAR}," +
      s"${TimeSeriesRecord.CHUNK_MONTH},${TimeSeriesRecord.CHUNK_DAY}"

    val solrOpts = Map(
      "zkhost" -> options.zkHosts,
      "collection" -> options.timeseriesCollectionName,
      /*   "splits" -> "true",
         "split_field"-> "name",
         "splits_per_shard"-> "50",*/
      "fields" -> fields,
      "filters" -> s"${TimeSeriesRecord.CHUNK_COMPACTION_RUNNING}:$jobId"
    )
    logger.info("solrOpts : {}", solrOpts.mkString("\n{", "\n", "}"))
    SparkSolrUtils.loadFromSolR(spark, solrOpts)
  }

  private def saveNewChunksToSolR(timeseriesDS: Dataset[TimeSeriesRecord]) = {


    import timeseriesDS.sparkSession.implicits._

    logger.info(s"start saving new chunks to ${options.timeseriesCollectionName}")
    val savedDF = timeseriesDS
      .map(r => (
        r.getId,
        r.getField(TimeSeriesRecord.CHUNK_YEAR).asInteger(),
        r.getField(TimeSeriesRecord.CHUNK_MONTH).asInteger(),
        r.getField(TimeSeriesRecord.CHUNK_DAY).asInteger(),
        r.getField(TimeSeriesRecord.CODE_INSTALL).asString(),
        r.getField(TimeSeriesRecord.SENSOR).asString(),
        r.getField(TimeSeriesRecord.METRIC_NAME).asString(),
        r.getField(TimeSeriesRecord.CHUNK_VALUE).asString(),
        r.getField(TimeSeriesRecord.CHUNK_START).asLong(),
        r.getField(TimeSeriesRecord.CHUNK_END).asLong(),
        r.getField(TimeSeriesRecord.CHUNK_WINDOW_MS).asLong(),
        r.getField(TimeSeriesRecord.CHUNK_SIZE).asInteger(),
        r.getField(TimeSeriesRecord.CHUNK_FIRST_VALUE).asDouble(),
        r.getField(TimeSeriesRecord.CHUNK_AVG).asDouble(),
        r.getField(TimeSeriesRecord.CHUNK_MIN).asDouble(),
        r.getField(TimeSeriesRecord.CHUNK_MAX).asDouble(),
        r.getField(TimeSeriesRecord.CHUNK_SUM).asDouble(),
        r.getField(TimeSeriesRecord.CHUNK_TREND).asBoolean(),
        r.getField(TimeSeriesRecord.CHUNK_OUTLIER).asBoolean(),
        if (r.hasField(TimeSeriesRecord.CHUNK_SAX)) r.getField(TimeSeriesRecord.CHUNK_SAX).asString() else "",
        r.getField(TimeSeriesRecord.CHUNK_ORIGIN).asString())
      )
      .toDF(TimeSeriesRecord.CHUNK_ID,
        TimeSeriesRecord.CHUNK_YEAR,
        TimeSeriesRecord.CHUNK_MONTH,
        TimeSeriesRecord.CHUNK_DAY,
        TimeSeriesRecord.CODE_INSTALL,
        TimeSeriesRecord.SENSOR,
        TimeSeriesRecord.METRIC_NAME,
        TimeSeriesRecord.CHUNK_VALUE,
        TimeSeriesRecord.CHUNK_START,
        TimeSeriesRecord.CHUNK_END,
        TimeSeriesRecord.CHUNK_WINDOW_MS,
        TimeSeriesRecord.CHUNK_SIZE,
        TimeSeriesRecord.CHUNK_FIRST_VALUE,
        TimeSeriesRecord.CHUNK_AVG,
        TimeSeriesRecord.CHUNK_MIN,
        TimeSeriesRecord.CHUNK_MAX,
        TimeSeriesRecord.CHUNK_SUM,
        TimeSeriesRecord.CHUNK_TREND,
        TimeSeriesRecord.CHUNK_OUTLIER,
        TimeSeriesRecord.CHUNK_SAX,
        TimeSeriesRecord.CHUNK_ORIGIN)

    savedDF.write
      .format("solr")
      .options(Map(
        "zkhost" -> options.zkHosts,
        "collection" -> options.timeseriesCollectionName
      ))
      .save()

    // Explicit commit to make sure all docs are visible
    val solrCloudClient = SolrSupport.getCachedCloudClient(options.zkHosts)
    val response = solrCloudClient.commit(options.timeseriesCollectionName, true, true)
    logger.info(s"done saving new chunks : ${response.toString}")

    savedDF
  }

  private def chunkIntoSeveralTimeSeriesRecord(metricType: String, metricName: String,
                                   values: ArrayDF[String],
                                   starts: ArrayDF[Long],
                                   ends: ArrayDF[Long],
                                   sizes: ArrayDF[Long]) = {
    if (
      values.length != starts.length ||
        values.length != ends.length ||
        values.length != sizes.length
    ) {
      throw new RuntimeException("number of chunk_value, chunk_start, chunk_end and chunk_size are not the same ! This should never happen")
    }
    val numberOfChunkToCompact = values.length
    val numberOfChunkToCompactMinusOne = numberOfChunkToCompact - 1

    val chunks = (for (i <- 0 to numberOfChunkToCompactMinusOne) yield {
      CompactionChunkInfo(values(i), starts(i), ends(i), sizes(i))
    }).flatMap(_.getPoints())
      .sortBy(c => c.getTimestamp)
      .grouped(options.chunkSize)
      .map(chunkPoints => {
        val builder = new MetricTimeSeries.Builder(metricName, metricType)
        chunkPoints.foreach(p => builder.point(p.getTimestamp, p.getValue))
        new TimeSeriesRecord(builder.build())
      })

    if (logger.isTraceEnabled) {
      chunks.foreach(t => {
        logger.trace(s"${metricName} : new chunk size ${t.getChunkSize}, start ${t.getTimeSeries.getStart} - end ${t.getTimeSeries.getEnd}")
      })
    }
    chunks.toList
  }

  /**
   * determine grouping criteria depending on number of points
   * If 10 per day, 300 per month, 3000 per year then group by year for those metrics
   * If 10000 per day then group by day for those metrics
   *
   * @param sparkSession
   * @param solrDf
   * @return
   */
  private def mergeChunks(sparkSession: SparkSession, solrDf: DataFrame): Dataset[TimeSeriesRecord] = {
    val maxNumberOfPointInPartition = 100000L //TODO this means we can not compact chunks of more than 100000
//    solrDf.cache()
    val dailyChunks: Dataset[TimeSeriesRecord] = chunkDailyMetrics(solrDf, maxNumberOfPointInPartition)
//    val monthlyChunks: Dataset[TimeSeriesRecord] = chunkMonthlyMetrics(solrDf, maxNumberOfPointInPartition)
//    val yearlyChunks: Dataset[TimeSeriesRecord] = chunkYearlyMetrics(solrDf, maxNumberOfPointInPartition)

//    dailyChunks
//      .union(monthlyChunks)
//      .union(yearlyChunks)
    dailyChunks

//    val monthlyMetrics: Dataset[String] = findMonthlyMetrics(maxNumberOfPointInPartition)
//    val yearlyMetrics: Dataset[String] = findYearlyMetrics(maxNumberOfPointInPartition)
  }

  private def chunkDailyMetrics(solrDf: DataFrame, maxNumberOfPointInPartition: Long): Dataset[TimeSeriesRecord] = {
//    val daylyMetrics: Dataset[String] = findDaylyMetrics(solrDf, maxNumberOfPointInPartition)
    val groupedChunkDf = solrDf
      .groupBy(
        col(TimeSeriesRecord.METRIC_NAME),
        col(TimeSeriesRecord.CHUNK_YEAR),
        col(TimeSeriesRecord.CHUNK_MONTH),
        col(TimeSeriesRecord.CHUNK_DAY))
      .agg(
        sum(col(TimeSeriesRecord.CHUNK_SIZE)).as(totalNumberOfPointColumnName),
        f.collect_list(col(TimeSeriesRecord.CHUNK_START)).as(startsColumnName),
        f.collect_list(col(TimeSeriesRecord.CHUNK_VALUE)).as(valuesColumnName),
        f.collect_list(col(TimeSeriesRecord.CHUNK_END)).as(endsColumnName),
        f.collect_list(col(TimeSeriesRecord.CHUNK_SIZE)).as(sizesColumnName),
        f.first(col(TimeSeriesRecord.METRIC_NAME)).as(nameColumnName)
      )

    import groupedChunkDf.sparkSession.implicits._

    groupedChunkDf
      .rdd
      .flatMap(mergeChunksIntoSeveralChunk)
      .map(calculMetrics)
      .toDS()
  }

  def findDaylyMetrics(solrDf: DataFrame, maxNumberOfPointInPartition: Long): Dataset[String] = {
    null//TODO
  }

  private def calculMetrics(timeSerie: TimeSeriesRecord): TimeSeriesRecord = {
    // Init the Timeserie processor
    val tsProcessor = new TimeseriesConverter()
    val context = new HistorianContext(tsProcessor)
    context.setProperty(TimeseriesConverter.GROUPBY.getName, TimeSeriesRecord.METRIC_NAME)
    context.setProperty(TimeseriesConverter.METRIC.getName,
      s"first;min;max;count;sum;avg;count;trend;outlier;sax:${options.saxAlphabetSize},0.01,${options.saxStringLength}")
    tsProcessor.init(context)
    tsProcessor.computeValue(timeSerie)
    tsProcessor.computeMetrics(timeSerie)
    EvoaUtils.setBusinessFields(timeSerie)
    EvoaUtils.setDateFields(timeSerie)
    EvoaUtils.setHashId(timeSerie)
    EvoaUtils.setChunkOrigin(timeSerie, jobId)
    timeSerie
  }

  private def mergeChunksIntoSeveralChunk(r: Row): List[TimeSeriesRecord] = {
    val name = r.getAs[String](nameColumnName)
    val values = r.getAs[ArrayDF[String]](valuesColumnName)
    val starts = r.getAs[ArrayDF[Long]](startsColumnName)
    val ends = r.getAs[ArrayDF[Long]](endsColumnName)
    val sizes = r.getAs[ArrayDF[Long]](sizesColumnName)
    val totalPoints = r.getAs[Long](totalNumberOfPointColumnName)
    logger.trace(s"A total of points of $totalPoints")
    val chunked = chunkIntoSeveralTimeSeriesRecord("evoa_measure", name, values, starts, ends, sizes)
    chunked
  }

  /**
   * save job report
   * @param solrChunks
   * @return
   */
  def saveReportJobAfterTagging(solrChunks: DataFrame) = {
    logger.info(s"start saving start report of job $jobId to ${options.reportCollectionName}")
    val reportDoc = solrChunks.select(
      f.col(TimeSeriesRecord.METRIC_NAME)
    )
      .agg(
        f.lit(jobId).as(CompactorJobReport.JOB_ID),
        f.lit(CompactorJobReport.JOB_TYPE_VALUE).as(CompactorJobReport.JOB_TYPE),
        f.lit(jobStart).as(CompactorJobReport.JOB_START),
        f.lit(JobStatus.RUNNING.toString).as(CompactorJobReport.JOB_STATUS),
        f.count(TimeSeriesRecord.METRIC_NAME).as(CompactorJobReport.JOB_NUMBER_OF_CHUNK_INPUT),
        f.countDistinct(TimeSeriesRecord.METRIC_NAME).as(CompactorJobReport.JOB_TOTAL_METRICS_RECHUNKED),
        f.lit(options.toJsonStr).as(CompactorJobReport.JOB_CONF)
      )
    reportDoc.write
      .format("solr")
      .options(Map(
        "zkhost" -> options.zkHosts,
        "collection" -> options.reportCollectionName
      )).save()

    // Explicit commit to make sure all docs are visible
    val solrCloudClient = SolrSupport.getCachedCloudClient(options.zkHosts)
    val response = solrCloudClient.commit(options.reportCollectionName, true, true)
    logger.info(s"done saving start report of job $jobId to ${options.reportCollectionName} :\n{}", response)
    if (logger.isTraceEnabled) (
      reportDoc.show(false)
    )

    reportDoc
  }

  def buildJobId(start:Long) = {
    s"compaction-$start"//TODO use string date
  }

  def saveReportJobSuccess(savedDF: DataFrame) = {
    logger.info(s"start saving success report of job $jobId to ${options.reportCollectionName}")
    val numberOfChunkOutput: Long = savedDF.count()
    val jobEnd = System.currentTimeMillis()
    val updateDoc = new SolrInputDocument
    updateDoc.setField(CompactorJobReport.JOB_ID, jobId)
//    updateDoc.addField(CompactorJobReport.JOB_ELAPSED, new util.HashMap[String, Long](1) {
//      {
//        put("set", jobEnd - jobStart);
//      }
//    })
//    updateDoc.addField(CompactorJobReport.JOB_END, new util.HashMap[String, Long](1) {
//      {
//        put("set", jobEnd);
//      }
//    })
//    updateDoc.addField(CompactorJobReport.JOB_NUMBER_OF_CHUNK_OUTPUT, new util.HashMap[String, Long](1) {
//      {
//        put("set", numberOfChunkOutput);
//      }
//    })
    updateDoc.setField(CompactorJobReport.JOB_ELAPSED, jobEnd - jobStart)
    updateDoc.setField(CompactorJobReport.JOB_END, jobEnd)
    updateDoc.setField(CompactorJobReport.JOB_NUMBER_OF_CHUNK_OUTPUT, numberOfChunkOutput)
    updateDoc.setField(CompactorJobReport.JOB_STATUS, new util.HashMap[String, String](1) {
      {
        put("set", JobStatus.SUCCEEDED.toString);
      }
    })

    val solrCloudClient = SolrSupport.getCachedCloudClient(options.zkHosts)
    val rsp = solrCloudClient.add(options.reportCollectionName, updateDoc)
    handleSolrResponse(rsp)
    val rsp2 = solrCloudClient.commit(options.reportCollectionName, true, true)
    handleSolrResponse(rsp2)
  }


  def tagChunksToBeCompacted(sparkSession: SparkSession) = {
    val solrChunks: DataFrame = loadChunksToBeTaggegFromSolR(sparkSession)
    logger.info(s"start tagging chunks to be compacted by job '$jobId' to collection ${options.timeseriesCollectionName}")
    import solrChunks.sparkSession.implicits._
    val chunkIds: Dataset[String] = solrChunks.select(
      f.col(TimeSeriesRecord.CHUNK_ID)
    ).as[String]

    val taggedChunks: RDD[SolrInputDocument] = buildTaggegSolrDocRdd(chunkIds)
    HurenceSolrSupport.indexDocs(
      options.zkHosts,
      options.timeseriesCollectionName,
      1000,
      taggedChunks
    )

    logger.info(s"done tagging chunks to be compacted by job '$jobId' to collection ${options.timeseriesCollectionName}")
    val solrCloudClient = SolrSupport.getCachedCloudClient(options.zkHosts)
    val response = solrCloudClient.commit(options.timeseriesCollectionName, true, true)//TODO is this necessary ?
    handleSolrResponse(response)
  }

  /**
   *
   * @param chunkIds containing ids of doc to tag
   * @return
   */
  def buildTaggegSolrDocRdd(chunkIds: Dataset[String]) = {
    val addJobIdTag: util.Map[String, String] = new util.HashMap[String, String](1) {
      {
        put("add", jobId);
      }
    }
    chunkIds.rdd.map(id => {
      val updateDoc = new SolrInputDocument
      updateDoc.setField(TimeSeriesRecord.CHUNK_ID, id)
      updateDoc.setField(TimeSeriesRecord.CHUNK_COMPACTION_RUNNING, addJobIdTag)
      updateDoc
    })
    //(Encoders.bean(classOf[SolrInputDocument]))
  }

  def saveReportJobStarting() = {
    //TODO
  }

  def handleSolrResponse(response: UpdateResponse) = {
    logger.info(s"response : \n{}", response)
    val statusCode = response.getStatus
    if (statusCode == 0) {
      logger.info(s"request succeeded, elapsed time is {},\n header : \n${response.getResponseHeader}\n body ${response.getResponse}", response.getElapsedTime)
    } else {
      logger.error(s"error during in response, header : \n${response.getResponseHeader}\n body ${response.getResponse}")
      throw response.getException
    }
  }

  def deleteTaggedChunks() = {
    val solrCloudClient = SolrSupport.getCachedCloudClient(options.zkHosts)
    val query = s"${TimeSeriesRecord.CHUNK_COMPACTION_RUNNING}:$jobId"
    // Explicit commit to make sure all docs are visible
    logger.info(s"will permantly delete docs matching $query from ${options.timeseriesCollectionName}}")
    solrCloudClient.deleteByQuery(options.timeseriesCollectionName, query)
    val rsp = solrCloudClient.commit(options.timeseriesCollectionName, true, true)
    handleSolrResponse(rsp)
  }

  /**
   * Compact chunks of historian
   */
  override def run(spark: SparkSession): Unit = {
    saveReportJobStarting()//TODO
    try {
      tagChunksToBeCompacted(spark)
      val timeseriesDS = loadTaggedChunksFromSolR(spark)
      saveReportJobAfterTagging(timeseriesDS)
      val mergedTimeseriesDS = mergeChunks(sparkSession = spark, timeseriesDS)
      val savedDF = saveNewChunksToSolR(mergedTimeseriesDS)
      //TODO be sure no error happenned ! try catch between each step (not lazy step ofc)
      //Be sure chunked data equal not chunked data as well (so we are sure of not loosing data) Maybe a count of points for each metrics (would be a first verif)
      deleteTaggedChunks()
      saveReportJobSuccess(savedDF)
    } catch {
      case ex: Throwable => {
        logger.error("Got some other kind of exception", ex)//TODO
      }
    }

  }
}
