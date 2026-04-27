package com.snelnieuws

import com.snelnieuws.api.NewsServlet
import com.snelnieuws.db.Database
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.service.SummarizedArticleConsumer
import org.scalatra.LifeCycle
import javax.servlet.ServletContext
import org.slf4j.LoggerFactory

class ScalatraBootstrap extends LifeCycle {

  private val logger = LoggerFactory.getLogger(classOf[ScalatraBootstrap])
  private var summarizedConsumer: Option[SummarizedArticleConsumer] = None

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

    val kafkaCfg = SummarizedImportKafkaConfig.load()
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
    super.destroy(context)
  }
}
