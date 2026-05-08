package com.snelnieuws.service

import org.slf4j.LoggerFactory

import java.util.concurrent.{
  ArrayBlockingQueue,
  ExecutorService,
  Executors,
  ThreadFactory,
  TimeUnit
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/** Background image-download pump.
  *
  * The consumer (and POST /v2/articles) compute the content-addressed
  * relative path for every article URL and write it straight onto
  * articles.url_to_image — *before* any bytes are fetched. This worker's
  * only job is to make those bytes appear at the matching path on NFS.
  * A failure here doesn't break the article: the servlet falls through
  * to the bundled fallback for paths that don't exist on disk yet.
  *
  * Bounded queue + drop-on-full keeps memory bounded under bursty
  * Kafka traffic. With dedup happening implicitly inside
  * ImageCacheService.resolveOrFetch (DB lookup before fetch), enqueueing
  * the same URL from multiple articles is cheap.
  */
class ImageDownloadWorker(
  imageCacheService: ImageCacheService,
  workerThreads: Int,
  queueCapacity: Int
) {

  private val logger  = LoggerFactory.getLogger(classOf[ImageDownloadWorker])
  private val started = new AtomicBoolean(false)
  private val running = new AtomicBoolean(false)

  private val queue   = new ArrayBlockingQueue[String](queueCapacity)
  private val dropped = new AtomicLong(0)

  // Sentinel pushed to each worker on shutdown to wake them out of
  // queue.take(). Distinct identity (eq comparison) so a real URL string
  // equal to the literal can never be misread as a stop signal.
  private val PoisonPill: String = new String("__shutdown__")

  private lazy val executor: ExecutorService =
    Executors.newFixedThreadPool(
      workerThreads,
      new ThreadFactory {
        private val counter = new AtomicLong(0)
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"image-download-worker-${counter.incrementAndGet()}")
          t.setDaemon(true)
          t
        }
      }
    )

  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      logger.info(
        s"Starting image download worker — threads=$workerThreads, queueCapacity=$queueCapacity"
      )
      running.set(true)
      var i = 0
      while (i < workerThreads) {
        executor.submit(new Runnable {
          override def run(): Unit = drainLoop()
        })
        i += 1
      }
    }
  }

  /** Best-effort enqueue. Returns false if the queue is full — the article
    * row keeps its content-addressed URL and the servlet will serve the
    * fallback for it until something else (a later identical-URL job, or a
    * backfill) downloads the bytes. */
  def enqueue(sourceUrl: String): Boolean = {
    val trimmed = Option(sourceUrl).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) return false
    if (!running.get()) {
      logger.debug(s"image worker not running; dropping url=$trimmed")
      return false
    }
    val ok = queue.offer(trimmed)
    if (!ok) {
      val total = dropped.incrementAndGet()
      // Log every Nth drop to avoid hot-path spam, but keep visibility.
      if (total == 1 || total % 100 == 0) {
        logger.warn(s"image-download queue full; dropped $total job(s) so far (latest=$trimmed)")
      }
    }
    ok
  }

  def stop(): Unit = {
    if (started.compareAndSet(true, false)) {
      logger.info("Stopping image download worker...")
      running.set(false)
      // Push one poison pill per worker so each take() returns. Using
      // offer() (not put()) — if the queue is full we'll just rely on the
      // running flag check inside drainLoop after the next take.
      var i = 0
      while (i < workerThreads) {
        queue.offer(PoisonPill)
        i += 1
      }
      executor.shutdown()
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          logger.warn("image worker did not terminate cleanly within 10s; forcing")
          executor.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          executor.shutdownNow()
          Thread.currentThread().interrupt()
      }
    }
  }

  /** Visible for tests — count of jobs that couldn't be enqueued because
    * the queue was full. */
  def droppedCount(): Long = dropped.get()

  /** Visible for tests — current queue depth. */
  def queueDepth(): Int = queue.size()

  private def drainLoop(): Unit = {
    while (running.get()) {
      try {
        val job = queue.take()
        if (job eq PoisonPill) {
          // Bail out of this worker; other workers exit independently
          // when they see their own poison.
          return
        }
        try {
          imageCacheService.resolveOrFetch(job) match {
            case Right(_) =>
              logger.debug(s"image fetched ok url=$job")
            case Left(_: ImageCacheService.RetryNotDueException) =>
              // Already logged at debug inside the service.
            case Left(e) =>
              // Service has already recorded the failure; downgrade to
              // info here so prod logs aren't dominated by upstream CDN
              // hiccups (those are expected).
              logger.info(s"image fetch failed url=$job: ${e.getMessage}")
          }
        } catch {
          case e: Throwable =>
            logger.error(s"unexpected image-worker error url=$job: ${e.getMessage}", e)
        }
      } catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          return
      }
    }
  }
}
