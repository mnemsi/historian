package com.hurence.historian

import java.util

import com.hurence.historian.ChunkCompactorJob.ChunkCompactorConf
import com.hurence.historian.IncreasingChunkSizeChunkCompactorJob2Test.LOGGER
import com.hurence.historian.solr.injector.GeneralSolrInjector
import com.hurence.historian.solr.util.SolrITHelper
import com.hurence.logisland.record.{Point, TimeSeriesRecord}
import com.hurence.unit5.extensions.{SolrExtension, SparkExtension}
import org.apache.solr.client.solrj.{SolrClient, SolrQuery}
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.common.params.SolrParams
import org.apache.spark.sql.{Dataset, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{BeforeAll, Test}
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.testcontainers.containers.DockerComposeContainer

import scala.collection.JavaConversions._

@ExtendWith(Array(classOf[SolrExtension], classOf[SparkExtension]))
class IncreasingChunkSizeChunkCompactorJob2Test(container: (DockerComposeContainer[SELF]) forSome {type SELF <: DockerComposeContainer[SELF]}) {

  val compactorConf = ChunkCompactorConf(SolrExtension.getZkUrl(container), SolrITHelper.COLLECTION_HISTORIAN,
    chunkSize = 10,
    saxAlphabetSize = 2,
    saxStringLength = 3,
    year = IncreasingChunkSizeChunkCompactorJob2Test.year,
    month = IncreasingChunkSizeChunkCompactorJob2Test.month,
    day = IncreasingChunkSizeChunkCompactorJob2Test.day)

//  val compactor = new ChunkCompactorJob(compactorConf)
  val compactor = new ChunkCompactorJobStrategy2(compactorConf)

  val metricA: String = IncreasingChunkSizeChunkCompactorJob2Test.metricA
  val metricB: String = IncreasingChunkSizeChunkCompactorJob2Test.metricB

  @Test
  def testCompactor(sparkSession: SparkSession, client: SolrClient) = {
    val start = System.currentTimeMillis();
    assertEquals(24, IncreasingChunkSizeChunkCompactorJob2Test.docsInSolr(client))
    //LOADING
    val loadedFromSolr = compactor.loadDataFromSolR(sparkSession, s"name:*")
    loadedFromSolr.cache()
    loadedFromSolr.show(100)
    assertEquals(24, loadedFromSolr.count())
    //CHUNKING
    val chunked = compactor.mergeChunks(sparkSession, loadedFromSolr)
    chunked.show(100)
    val records: util.List[TimeSeriesRecord] = chunked.collectAsList()
    assertEquals(4, records.size())
    val recordsA: List[TimeSeriesRecord] = records.filter(r => r.getMetricName == metricA).toList
    assertEquals(2, recordsA.size())
    val pointsA: List[Point] = recordsA.flatMap(_.getPoints)
    assertEquals(12, pointsA.size())
    //first
    assertEquals(1477895624866L, recordsA.get(0).getStartChunk)
    assertEquals(1477924224866L, recordsA.get(0).getEndChunk)
    assertEquals(622, recordsA.get(0).getFirstValue)
    //last
    assertEquals(1477925224866L, recordsA.get(1).getStartChunk)
    assertEquals(1477926224866L, recordsA.get(1).getEndChunk)
    assertEquals(288, recordsA.get(1).getFirstValue)
    val recordsB: List[TimeSeriesRecord] = records.filter(r => r.getMetricName == metricB).toList
    assertEquals(2, recordsB.size())
    val pointsB: List[Point] = recordsB.flatMap(_.getPoints)
    assertEquals(12, pointsB.size())
    //first
    assertEquals(1477895624866L, recordsB.get(0).getStartChunk)
    assertEquals(1477924224866L, recordsB.get(0).getEndChunk)
    assertEquals(622, recordsB.get(0).getFirstValue)
    //last
    assertEquals(1477925224866L, recordsB.get(1).getStartChunk)
    assertEquals(1477926224866L, recordsB.get(1).getEndChunk)
    assertEquals(288, recordsB.get(1).getFirstValue)
    assertEquals(pointsA, pointsB)

    //If we suppose ancient chunk are not deleted !
    //SAVING
    val savedDf = compactor.saveNewChunksToSolR(chunked)
    savedDf.show(100)
    assertEquals(28, IncreasingChunkSizeChunkCompactorJob2Test.docsInSolr(client))
    val end = System.currentTimeMillis();
    LOGGER.info("compactor finished in {} s", (end - start) / 1000)
  }
}

object IncreasingChunkSizeChunkCompactorJob2Test {
  private val LOGGER = LoggerFactory.getLogger(classOf[IncreasingChunkSizeChunkCompactorJob2Test])
  private val year = 1999;
  private val month = 10;
  private val day = 1;
  private val chunkOrigin = "logisland";
  private val metricA = "temp_a"
  private val metricB = "temp_b"

  @BeforeAll
  def initHistorianAndDeployVerticle(client: SolrClient): Unit = {
    SolrITHelper.initHistorianSolr(client)
    LOGGER.info("Indexing some documents in {} collection", SolrITHelper.COLLECTION_HISTORIAN)
    val injector: GeneralSolrInjector = new GeneralSolrInjector()
    addSeveralChunksForMetric(injector, metricA)
    addSeveralChunksForMetric(injector, metricB)
    injector.injectChunks(client)
    LOGGER.info("Indexed some documents in {} collection", SolrITHelper.COLLECTION_HISTORIAN)
  }

   def addSeveralChunksForMetric(injector: GeneralSolrInjector, metric: String) = {
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477895624866L, 622)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477916224866L, -3)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477917224866L, 365)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477918224866L, 120)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477919224866L, 15)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477920224866L, -100)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477921224866L, 0)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477922224866L, 120)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477923224866L, 250)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477924224866L, 275)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477925224866L, 288)
      )
    )
    injector.addChunk(metric, year, month, day, chunkOrigin,
      util.Arrays.asList(
        new Point(0, 1477926224866L, 198)
      )
    )
  }

  def docsInSolr(client: SolrClient) = {
    val params: SolrParams = new SolrQuery("*:*");
    val rsp: QueryResponse = client.query(SolrITHelper.COLLECTION_HISTORIAN, params)
    rsp.getResults.getNumFound
  }
}





