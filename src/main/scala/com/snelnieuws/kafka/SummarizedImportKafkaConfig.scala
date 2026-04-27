package com.snelnieuws.kafka

import com.typesafe.config.{Config, ConfigFactory}

case class SummarizedImportKafkaConfig(
  bootstrapServers: String,
  topic: String,
  groupId: String,
  autoOffsetReset: String,
  enabled: Boolean
)

object SummarizedImportKafkaConfig {

  def load(config: Config = ConfigFactory.load()): SummarizedImportKafkaConfig = {
    val kafka = config.getConfig("kafka.summarized-import")
    SummarizedImportKafkaConfig(
      bootstrapServers = kafka.getString("bootstrap-servers"),
      topic            = kafka.getString("topic"),
      groupId          = kafka.getString("group-id"),
      autoOffsetReset  = kafka.getString("auto-offset-reset"),
      enabled          = kafka.getBoolean("enabled")
    )
  }
}
