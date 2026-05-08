package com.snelnieuws.service

import com.snelnieuws.repository.ImageCacheRepository
import org.slf4j.LoggerFactory

import java.nio.file.{Files, NoSuchFileException, Path, Paths}
import java.time.OffsetDateTime
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/** Periodically removes expired image_cache rows + their NFS files.
  *
  * Mirrors ArticleCleanupScheduler in shape: single-thread daemon
  * ScheduledExecutorService, fail-soft (logs and waits for the next tick),
  * configurable retention/interval. Retention is intentionally longer than
  * articles.cleanup.retention-hours so an article never points at a file
  * we've already deleted.
  *
  * Cleanup is per-row rather than a filesystem walk: the image_cache table
  * is the source of truth and the file delete is best-effort against it.
  * If a file is already gone (NoSuchFileException) we still drop the row.
  */
class ImageCacheCleanupScheduler(
  imageCacheRepository: ImageCacheRepository,
  rootDir: String,
  retentionHours: Long,
  intervalMinutes: Long,
  batchSize: Int = 500
) {

  private val logger  = LoggerFactory.getLogger(classOf[ImageCacheCleanupScheduler])
  private val started = new AtomicBoolean(false)

  private val rootPath: Path = Paths.get(rootDir).toAbsolutePath.normalize()

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "image-cache-cleanup-scheduler")
        t.setDaemon(true)
        t
      }
    })

  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      logger.info(
        s"Starting image cache cleanup scheduler — retentionHours=$retentionHours, " +
          s"intervalMinutes=$intervalMinutes, batchSize=$batchSize"
      )
      scheduler.scheduleAtFixedRate(
        () => runOnce(),
        intervalMinutes,
        intervalMinutes,
        TimeUnit.MINUTES
      )
    }
  }

  def stop(): Unit = {
    if (started.compareAndSet(true, false)) {
      logger.info("Stopping image cache cleanup scheduler...")
      scheduler.shutdown()
      try {
        if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
          scheduler.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          scheduler.shutdownNow()
          Thread.currentThread().interrupt()
      }
    }
  }

  /** Visible for tests — runs one cleanup tick synchronously. */
  def runOnce(): Unit = {
    val cutoff = OffsetDateTime.now().minusHours(retentionHours)
    imageCacheRepository.findDownloadedBefore(cutoff, batchSize) match {
      case Right(rows) if rows.isEmpty =>
        logger.debug(s"image cache cleanup: nothing older than $cutoff")
      case Right(rows) =>
        var fileDeletes = 0
        var rowDeletes  = 0
        rows.foreach { case (sourceUrl, relativePath) =>
          if (deleteFile(relativePath)) fileDeletes += 1
          imageCacheRepository.deleteByUrl(sourceUrl) match {
            case Right(n) => rowDeletes += n
            case Left(e) =>
              logger.warn(
                s"image cache cleanup: failed to delete row url=$sourceUrl: ${e.getMessage}"
              )
          }
        }
        logger.info(
          s"image cache cleanup: removed $rowDeletes row(s) and $fileDeletes file(s) older than $cutoff"
        )
      case Left(e) =>
        logger.error(s"image cache cleanup tick failed: ${e.getMessage}", e)
    }
  }

  private def deleteFile(relativePath: String): Boolean = {
    val target = rootPath.resolve(relativePath).normalize()
    if (!target.startsWith(rootPath)) {
      logger.warn(s"image cache cleanup: refusing to delete outside root: $relativePath")
      return false
    }
    try {
      Files.delete(target)
      true
    } catch {
      case _: NoSuchFileException =>
        // Already gone — still considered a success from the cleanup
        // scheduler's perspective so the row gets dropped on the next
        // statement.
        true
      case e: Exception =>
        logger.warn(s"image cache cleanup: file delete failed path=$relativePath: ${e.getMessage}")
        false
    }
  }
}
