package com.snelnieuws.service

import com.snelnieuws.repository.ImageCacheRepository
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.net.http.HttpClient
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

class ImageDownloadWorkerSpec extends AnyWordSpec with Matchers with Eventually {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(3, Seconds), interval = Span(50, Millis))

  // Repository whose transactor thunk explodes on access — proves the
  // worker test stays out of the DB path.
  private def explodingRepo(): ImageCacheRepository =
    new ImageCacheRepository({ throw new AssertionError("repo must not be used in this test") })

  private val baseConfig = ImageCacheConfig(
    rootDir             = "/tmp/img-worker-spec",
    downloadTimeoutMs   = 1000,
    maxBytes            = 1024,
    userAgent           = "test/1.0",
    maxAttempts         = 3,
    retryBackoffMinutes = 30
  )

  /** Stub service that records every URL it was asked to fetch and lets
    * the test choose the result. Subclasses ImageCacheService so the worker
    * sees a real type — overriding only resolveOrFetch. */
  private class RecordingService(
    decide: String => Either[Throwable, ImageDownloadResult]
  ) extends ImageCacheService(explodingRepo(), HttpClient.newHttpClient(), baseConfig) {
    val calls = new ConcurrentLinkedQueue[String]()
    override def resolveOrFetch(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
      calls.add(sourceUrl)
      decide(sourceUrl)
    }
  }

  "ImageDownloadWorker.enqueue" should {
    "drain enqueued URLs through resolveOrFetch" in {
      val ok = (u: String) => Right(ImageDownloadResult(u, Some("image/jpeg"), 10L))
      val svc = new RecordingService(ok)
      val worker = new ImageDownloadWorker(svc, workerThreads = 2, queueCapacity = 16)
      worker.start()
      try {
        (1 to 5).foreach(i => worker.enqueue(s"https://example.com/$i.jpg") shouldBe true)
        eventually {
          svc.calls.size shouldBe 5
        }
        worker.droppedCount() shouldBe 0L
      } finally {
        worker.stop()
      }
    }

    "skip empty URLs without enqueueing" in {
      val svc    = new RecordingService(_ => Right(ImageDownloadResult("x", None, 0L)))
      val worker = new ImageDownloadWorker(svc, workerThreads = 1, queueCapacity = 16)
      worker.start()
      try {
        worker.enqueue("")    shouldBe false
        worker.enqueue("   ") shouldBe false
        worker.enqueue(null)  shouldBe false
        Thread.sleep(50)
        svc.calls.size shouldBe 0
      } finally {
        worker.stop()
      }
    }

    "drop jobs when the queue is full instead of blocking" in {
      // Single thread, capacity=1, slow service: only one job can be in-flight,
      // queue has room for one. Subsequent enqueues during that window must
      // be dropped rather than block the caller.
      val gate = new CountDownLatch(1)
      val seen = new AtomicInteger(0)
      val slow: String => Either[Throwable, ImageDownloadResult] = _ => {
        seen.incrementAndGet()
        gate.await(2, TimeUnit.SECONDS) // hold the worker thread
        Right(ImageDownloadResult("x", None, 0L))
      }
      val svc    = new RecordingService(slow)
      val worker = new ImageDownloadWorker(svc, workerThreads = 1, queueCapacity = 1)
      worker.start()
      try {
        // First enqueue gets picked up by the worker thread immediately.
        worker.enqueue("u-1") shouldBe true
        // Wait for the worker to actually take(); without this, the next
        // enqueue would just fill the empty queue.
        eventually { seen.get() shouldBe 1 }
        // Slot is now empty — the next enqueue fills it.
        worker.enqueue("u-2") shouldBe true
        // Both worker and queue are saturated — these should drop.
        worker.enqueue("u-3") shouldBe false
        worker.enqueue("u-4") shouldBe false
        worker.droppedCount() shouldBe 2L
      } finally {
        gate.countDown()
        worker.stop()
      }
    }

    "stop cleanly without hanging" in {
      val svc    = new RecordingService(_ => Right(ImageDownloadResult("x", None, 0L)))
      val worker = new ImageDownloadWorker(svc, workerThreads = 2, queueCapacity = 8)
      worker.start()
      worker.enqueue("https://example.com/a.jpg")
      worker.stop()
      // After stop, further enqueues must be no-ops.
      worker.enqueue("https://example.com/late.jpg") shouldBe false
    }
  }
}
