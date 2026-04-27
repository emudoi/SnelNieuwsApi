package com.snelnieuws

import com.snelnieuws.api.NewsServlet
import com.snelnieuws.db.Database
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.service.{ArticleCleanupScheduler, SummarizedArticleConsumer}
import com.typesafe.config.ConfigFactory
import org.scalatra.LifeCycle
import javax.servlet.ServletContext
import org.slf4j.LoggerFactory

class ScalatraBootstrap extends LifeCycle {

  private val logger = LoggerFactory.getLogger(classOf[ScalatraBootstrap])
  private var summarizedConsumer: Option[SummarizedArticleConsumer] = None
  private var cleanupScheduler: Option[ArticleCleanupScheduler]      = None

  override def init(context: ServletContext): Unit = {
    logger.info("Initializing snel-nieuws-api servlets...")

    try {
      Database.migrate()
    } catch {
      case e: Exception =>
        logger.error("Database migration failed — cannot start without tables", e)
        throw e
    }

    context.mount(new NewsServlet, "/*")

    val rootCfg = ConfigFactory.load()

    val cleanupCfg = rootCfg.getConfig("articles.cleanup")
    if (cleanupCfg.getBoolean("enabled")) {
      val scheduler = new ArticleCleanupScheduler(
        retentionHours  = cleanupCfg.getLong("retention-hours"),
        intervalMinutes = cleanupCfg.getLong("interval-minutes")
      )
      scheduler.start()
      cleanupScheduler = Some(scheduler)
    } else {
      logger.info("Article cleanup scheduler is disabled (articles.cleanup.enabled=false)")
    }

    val kafkaCfg = SummarizedImportKafkaConfig.load(rootCfg)
    if (kafkaCfg.enabled) {
      try {
        val consumer = new SummarizedArticleConsumer(kafkaCfg)
        consumer.start()
        summarizedConsumer = Some(consumer)
      } catch {
        case e: Exception =>
          // Don't crash the API if Kafka is down — just log it.
          logger.error(s"Failed to start summarized-article consumer: ${e.getMessage}", e)
      }
    } else {
      logger.info("Summarized-article Kafka consumer is disabled (kafka.summarized-import.enabled=false)")
    }

    logger.info("snel-nieuws-api servlets initialized successfully")
  }

  override def destroy(context: ServletContext): Unit = {
    summarizedConsumer.foreach(_.stop())
    cleanupScheduler.foreach(_.stop())
    super.destroy(context)
  }
}
