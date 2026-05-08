package com.snelnieuws.model

import java.time.OffsetDateTime

case class ImageCacheRow(
  sourceUrl: String,
  relativePath: String,
  contentType: Option[String],
  sizeBytes: Option[Long],
  status: String,
  downloadedAt: Option[OffsetDateTime],
  lastAttemptAt: OffsetDateTime,
  attempts: Int
)

object ImageCacheStatus {
  val Downloaded = "downloaded"
  val Failed     = "failed"
}
