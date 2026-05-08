package com.snelnieuws.service

import com.snelnieuws.model.{ImageCacheRow, ImageCacheStatus}
import com.snelnieuws.repository.ImageCacheRepository
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import java.time.{Duration => JDuration, OffsetDateTime}
import scala.util.{Failure, Success, Try}

/** Configuration knobs for the image cache. Mirrors the `images` block in
  * application.conf one-for-one so wiring just reads each value once.
  */
case class ImageCacheConfig(
  rootDir: String,
  downloadTimeoutMs: Long,
  maxBytes: Long,
  userAgent: String,
  maxAttempts: Int,
  retryBackoffMinutes: Long
)

/** Result of a successful download — handed back to the worker so it can
  * also update the article row's url_to_image (already done implicitly via
  * the deterministic pathFor, but the caller may want metadata). */
case class ImageDownloadResult(
  relativePath: String,
  contentType: Option[String],
  sizeBytes: Long
)

/** Pure URL → relative-path math + the side-effecting fetch/read paths.
  *
  * Path scheme: `<aa>/<bb>/<sha256(source_url)><ext>`
  *   - aa, bb are the first 4 hex chars of the digest split into two pairs,
  *     used as fan-out directories so no single dir holds the whole catalog.
  *   - ext is taken from the source URL path when it is a recognised image
  *     extension; otherwise ".bin" (servlet sets Content-Type from the
  *     image_cache row's content_type column anyway).
  *
  * Determinism is the contract: SummarizedArticleConsumer rewrites
  * articles.url_to_image to /v2/images/<pathFor(url)> *before* the worker
  * runs, and the worker (later) writes bytes to that same path. The servlet
  * serves whatever exists at the path, falling back to the bundled logo
  * when nothing is there yet.
  */
class ImageCacheService(
  repository: ImageCacheRepository,
  httpClient: HttpClient,
  config: ImageCacheConfig
) {

  import ImageCacheService._

  private val logger = LoggerFactory.getLogger(classOf[ImageCacheService])

  // Resolved & normalised once so every readBytes path check is a cheap
  // startsWith comparison. Anything that escapes this prefix is rejected.
  private val rootPath: Path = Paths.get(config.rootDir).toAbsolutePath.normalize()

  /** Pure: relative path under rootDir for a given source URL. Never
    * touches disk or DB. Returns the same value for the same URL forever. */
  def pathFor(sourceUrl: String): String = {
    val digest = sha256Hex(sourceUrl)
    val ext    = extensionFor(sourceUrl)
    val a      = digest.substring(0, 2)
    val b      = digest.substring(2, 4)
    s"$a/$b/$digest$ext"
  }

  /** Same as pathFor but with the public-route prefix. Used at write-time
    * by the consumer / create endpoint to populate articles.url_to_image. */
  def relativeUrlFor(sourceUrl: String): String =
    s"/v2/images/${pathFor(sourceUrl)}"

  /** Bypass URL for empty / unusable source URLs. */
  val fallbackRelativeUrl: String = "/v2/images/_fallback"

  /** Idempotent fetch. Returns the relative path on success. Behaviour:
    *
    *  - Row exists and status=downloaded: no-op, returns relative_path.
    *  - Row exists and status=failed but not retry-eligible: returns Left.
    *  - Otherwise: downloads, writes atomically to NFS, upserts the row.
    *
    *  On any download/write failure, the row is upserted as failed so
    *  attempts/last_attempt_at advance and the retry policy can throttle. */
  def resolveOrFetch(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
    val trimmed = sourceUrl.trim
    if (trimmed.isEmpty) {
      Left(new IllegalArgumentException("source URL is empty"))
    } else {
      repository.findByUrl(trimmed) match {
        case Left(e) => Left(e)
        case Right(Some(row)) if row.status == ImageCacheStatus.Downloaded =>
          Right(
            ImageDownloadResult(
              relativePath = row.relativePath,
              contentType  = row.contentType,
              sizeBytes    = row.sizeBytes.getOrElse(0L)
            )
          )
        case Right(rowOpt) =>
          val tooSoon = rowOpt.exists(r => !isRetryEligible(r))
          if (tooSoon) {
            val msg = s"image cache: not retry-eligible for url=$trimmed (attempts=${rowOpt.map(_.attempts).getOrElse(0)})"
            logger.debug(msg)
            Left(new RetryNotDueException(msg))
          } else {
            download(trimmed)
          }
      }
    }
  }

  /** Returns true if the row should be retried right now. New rows
    * (passed in via Some) get their attempts checked against maxAttempts;
    * the backoff window is enforced from last_attempt_at. */
  def isRetryEligible(row: ImageCacheRow): Boolean = {
    val attemptsOk = row.attempts < config.maxAttempts
    val backoffOk =
      row.lastAttemptAt.isBefore(OffsetDateTime.now().minusMinutes(config.retryBackoffMinutes))
    attemptsOk && backoffOk
  }

  /** Read bytes for a relative path on NFS. Rejects path traversal — the
    * resolved path must canonicalise to a descendant of rootDir. Returns
    * Left(NoSuchFileException) when the file doesn't exist on disk so the
    * servlet can decide to serve the fallback. */
  def readBytes(relativePath: String): Either[Throwable, (Array[Byte], Option[String])] = {
    val safe = canonicalizeUnderRoot(relativePath)
    safe match {
      case Left(e) => Left(e)
      case Right(target) =>
        if (!Files.exists(target)) {
          Left(new java.nio.file.NoSuchFileException(target.toString))
        } else {
          try {
            val bytes = Files.readAllBytes(target)
            // Prefer the recorded content_type from image_cache; fall back
            // to filesystem probe for entries written before that column
            // was populated (e.g. tests that touch the disk directly).
            val ct = repository.findByRelativePath(relativePath) match {
              case Right(Some(r)) => r.contentType
              case _              => Option(Files.probeContentType(target))
            }
            Right((bytes, ct))
          } catch {
            case e: Exception => Left(e)
          }
        }
    }
  }

  // ───────────────────────────── internals ─────────────────────────────

  private def download(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
    val relPath = pathFor(sourceUrl)
    Try {
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(sourceUrl))
        .timeout(JDuration.ofMillis(config.downloadTimeoutMs))
        .header("User-Agent", config.userAgent)
        .GET()
        .build()

      val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
      if (resp.statusCode() / 100 != 2) {
        throw new RuntimeException(
          s"non-2xx status ${resp.statusCode()} fetching $sourceUrl"
        )
      }
      val bytes = resp.body()
      if (bytes.length.toLong > config.maxBytes) {
        throw new RuntimeException(
          s"image exceeds max-bytes (${bytes.length} > ${config.maxBytes}) at $sourceUrl"
        )
      }
      val contentType = Option(resp.headers().firstValue("Content-Type").orElse(null))
        .map(_.split(";")(0).trim)
        .filter(_.nonEmpty)

      writeAtomic(relPath, bytes)
      ImageDownloadResult(
        relativePath = relPath,
        contentType  = contentType,
        sizeBytes    = bytes.length.toLong
      )
    } match {
      case Success(result) =>
        repository.upsertDownloaded(
          sourceUrl    = sourceUrl,
          relativePath = result.relativePath,
          contentType  = result.contentType,
          sizeBytes    = result.sizeBytes
        ) match {
          case Right(_) => Right(result)
          case Left(e) =>
            // The bytes are on disk, but the bookkeeping write failed.
            // Don't unwind the file — a later retry will simply upsert
            // the row and the servlet will keep serving the bytes.
            logger.warn(
              s"image_cache row upsert failed after successful download url=$sourceUrl: ${e.getMessage}"
            )
            Right(result)
        }
      case Failure(e) =>
        logger.warn(s"image download failed url=$sourceUrl: ${e.getMessage}")
        repository.upsertFailed(sourceUrl, relPath) match {
          case Right(_) => Left(e)
          case Left(e2) =>
            logger.error(s"image_cache failure-bookkeeping also failed url=$sourceUrl: ${e2.getMessage}")
            Left(e)
        }
    }
  }

  private def writeAtomic(relativePath: String, bytes: Array[Byte]): Unit = {
    val target = rootPath.resolve(relativePath).normalize()
    if (!target.startsWith(rootPath)) {
      // Defense-in-depth — pathFor never produces traversing paths, but a
      // future code path that bypasses pathFor mustn't be able to escape.
      throw new SecurityException(s"refusing to write outside root: $target")
    }
    Files.createDirectories(target.getParent)
    val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
    Files.write(tmp, bytes)
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        // NFS subdir provisioner sometimes refuses ATOMIC_MOVE across
        // an extended attribute boundary; the fallback REPLACE_EXISTING
        // is still good enough — readers either see the old file or the
        // fully-written new one, never a half-written byte stream.
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private def canonicalizeUnderRoot(relativePath: String): Either[Throwable, Path] = {
    if (relativePath == null || relativePath.isEmpty) {
      Left(new IllegalArgumentException("relative path is empty"))
    } else if (relativePath.startsWith("/")) {
      Left(new IllegalArgumentException(s"relative path must not be absolute: $relativePath"))
    } else {
      val resolved = rootPath.resolve(relativePath).normalize()
      if (!resolved.startsWith(rootPath)) {
        Left(new SecurityException(s"path traversal rejected: $relativePath"))
      } else {
        Right(resolved)
      }
    }
  }
}

object ImageCacheService {

  // Lifted out of resolveOrFetch.fail-path so callers can pattern-match
  // distinctly from arbitrary network errors and choose to suppress logs.
  class RetryNotDueException(msg: String) extends RuntimeException(msg)

  // Conservative allow-list. Anything else falls back to ".bin" — the
  // servlet still serves correctly because Content-Type comes from the
  // image_cache row, not the filename.
  private val KnownImageExtensions: Set[String] =
    Set("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "heic")

  private val HexAlphabet: Array[Char] = "0123456789abcdef".toCharArray

  def sha256Hex(input: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val out = md.digest(input.getBytes("UTF-8"))
    val sb  = new StringBuilder(out.length * 2)
    var i   = 0
    while (i < out.length) {
      val b = out(i) & 0xff
      sb.append(HexAlphabet(b >>> 4))
      sb.append(HexAlphabet(b & 0x0f))
      i += 1
    }
    sb.toString
  }

  // Extract a sane extension from the URL's path component, ignoring query
  // strings and fragments. Returns including the leading dot, e.g. ".jpg".
  def extensionFor(sourceUrl: String): String = {
    val path = Try(URI.create(sourceUrl).getPath).toOption.filter(_ != null).getOrElse("")
    val lastSlash = path.lastIndexOf('/')
    val fileName  = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    val lastDot   = fileName.lastIndexOf('.')
    if (lastDot < 0 || lastDot == fileName.length - 1) ".bin"
    else {
      val rawExt = fileName.substring(lastDot + 1).toLowerCase
      if (KnownImageExtensions.contains(rawExt)) s".$rawExt" else ".bin"
    }
  }

  /** Build the default HttpClient. Follows redirects (CDNs love 302s) and
    * caps connect time at the configured download timeout. The per-request
    * total timeout is set on the request itself in download(). */
  def defaultHttpClient(config: ImageCacheConfig): HttpClient =
    HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(JDuration.ofMillis(config.downloadTimeoutMs))
      .build()
}
