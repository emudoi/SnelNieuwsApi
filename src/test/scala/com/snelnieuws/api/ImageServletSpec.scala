package com.snelnieuws.api

import com.snelnieuws.repository.ImageCacheRepository
import com.snelnieuws.service.{ImageCacheConfig, ImageCacheService}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.http.HttpClient
import java.nio.file.{Files, NoSuchFileException}
import java.util.concurrent.atomic.AtomicReference

/** Tests against the real ImageServlet with a configurable stub
  * ImageCacheService. The fallback resource is loaded from the actual
  * classpath (src/main/resources/static).
  *
  * One servlet is registered for the whole suite — Jetty refuses to mount
  * two servlets at the same path inside a single context, which is what
  * happens if you call addServlet inside each spec body. Tests instead
  * mutate the shared `cannedRef` to drive different behaviour. */
class ImageServletSpec extends AnyWordSpec with ScalatraSuite with Matchers {

  // Repository whose transactor explodes — proves the servlet path doesn't
  // touch the DB layer.
  private def explodingRepo(): ImageCacheRepository =
    new ImageCacheRepository({ throw new AssertionError("repo must not be used") })

  private val baseConfig = ImageCacheConfig(
    rootDir             = Files.createTempDirectory("img-servlet-spec-").toString,
    downloadTimeoutMs   = 1000,
    maxBytes            = 1024,
    userAgent           = "test/1.0",
    maxAttempts         = 3,
    retryBackoffMinutes = 30
  )

  // Per-test canned response for `readBytes`. Defaults to "miss" so any
  // path the test hasn't pre-seeded comes back as NoSuchFileException →
  // the servlet should fall through to the fallback.
  private val cannedRef =
    new AtomicReference[Map[String, Either[Throwable, (Array[Byte], Option[String])]]](Map.empty)

  private object configurableSvc
      extends ImageCacheService(explodingRepo(), HttpClient.newHttpClient(), baseConfig) {
    override def readBytes(relativePath: String) =
      cannedRef.get().getOrElse(
        relativePath,
        Left(new NoSuchFileException(relativePath))
      )
  }

  addServlet(new ImageServlet(configurableSvc), "/v2/images/*")

  private def reset(): Unit = cannedRef.set(Map.empty)

  "GET /v2/images/_fallback" should {
    "return the bundled PNG with a long Cache-Control" in {
      reset()
      get("/v2/images/_fallback") {
        status shouldBe 200
        header("Content-Type") should startWith("image/png")
        header("Cache-Control") shouldBe "public, max-age=86400"
      }
    }
  }

  "GET /v2/images/<path>" should {
    "return cached bytes with the immutable Cache-Control on hit" in {
      val rel    = "ab/cd/abcdef0123456789.jpg"
      val bytes  = Array[Byte](9, 8, 7, 6)
      cannedRef.set(Map(rel -> Right((bytes, Some("image/jpeg")))))
      get(s"/v2/images/$rel") {
        status shouldBe 200
        // Scalatra's render pipeline adds `;charset=utf-8` to all
        // response Content-Types regardless of payload type. iOS's
        // AsyncImage and browsers ignore the charset suffix for image
        // types, so we only assert the media type prefix here.
        header("Content-Type") should startWith("image/jpeg")
        header("Cache-Control") shouldBe "public, max-age=31536000, immutable"
        bodyBytes.toSeq shouldBe bytes.toSeq
      }
    }

    "fall through to fallback bytes with a short TTL on missing-file" in {
      reset()
      get("/v2/images/ab/cd/missing.jpg") {
        status shouldBe 200
        header("Content-Type") should startWith("image/png")
        header("Cache-Control") shouldBe "public, max-age=60"
      }
    }

    "return 400 for path traversal attempts" in {
      cannedRef.set(Map("evil" -> Left(new SecurityException("nope"))))
      get("/v2/images/evil") {
        status shouldBe 400
      }
    }
  }
}
