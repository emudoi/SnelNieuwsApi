package com.snelnieuws.model

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

/**
 * Mirror of `SummarizedArticleExport` in emudoi-snelnieuws-ingestion-api.
 * Field shape must stay byte-compatible with the producer.
 */
case class SummarizedArticleExport(
  author: Option[String],
  title: String,
  description: Option[String],
  url: String,
  urlToImage: Option[String],
  publishedAt: String,
  createdAt: String,
  category: Option[String]
)

object SummarizedArticleExport {
  implicit val encoder: Encoder[SummarizedArticleExport] = deriveEncoder
  implicit val decoder: Decoder[SummarizedArticleExport] = deriveDecoder
}

case class SummarizedArticleExportEvent(
  eventType: String,
  article: SummarizedArticleExport
)

object SummarizedArticleExportEvent {
  implicit val encoder: Encoder[SummarizedArticleExportEvent] = deriveEncoder
  implicit val decoder: Decoder[SummarizedArticleExportEvent] = deriveDecoder
}
