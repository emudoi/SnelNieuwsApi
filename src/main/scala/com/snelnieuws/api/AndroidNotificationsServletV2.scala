package com.snelnieuws.api

import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.model.{AndroidSubscribeRequest, RegisterClientRequest}
import com.snelnieuws.repository.AppClientRepository
import com.snelnieuws.service.AndroidNotificationService
import org.json4s.{DefaultFormats, Formats, MappingException}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.Try

/** Android-specific surface, mounted at `/v2/android/`. Fully separate
  * from `NewsServletV2` so the iOS code path (and its `^ios/[^\s]+$`
  * X-Client regex) stays untouched.
  *
  * Every request passes the same two-layer gate as the iOS v2 surface:
  *
  *   1. X-Client: android/<version>  — platform attestation. Filters out
  *      drive-by scanners; trivial to spoof but cheap to enforce.
  *   2. X-Client-Key: <uuid>         — install attestation. Looked up in
  *      `app_clients` (the same table iOS uses; the platform column lets
  *      us still tell installs apart). Exempt only for POST /clients/register.
  *
  * Routes:
  *   POST   /clients/register             — bootstrap; idempotent.
  *   POST   /notifications/subscribe      — record / refresh FCM token.
  *   DELETE /notifications/:deviceId      — unsubscribe a device.
  */
class AndroidNotificationsServletV2(
  androidNotificationService: AndroidNotificationService,
  appClientRepository: AppClientRepository,
  firebaseVerifier: FirebaseTokenVerifier
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[AndroidNotificationsServletV2])

  // Android only here. Mirrors the iOS regex shape — version is kept lax
  // (any non-empty token) so a routine bundle bump doesn't require a
  // backend update.
  private val ClientHeaderRe = """^android/[^\s]+$""".r

  // POST /v2/android/clients/register is the bootstrap — the app needs to
  // call it before it can present a known X-Client-Key.
  private val KeyExemptPaths: Set[String] = Set("/clients/register")

  before() {
    contentType = formats("json")

    val xClient = Option(request.getHeader("X-Client")).map(_.trim).getOrElse("")
    if (!ClientHeaderRe.pattern.matcher(xClient).matches()) {
      halt(Forbidden(Map("error" -> "missing or invalid X-Client header")))
    }

    if (!KeyExemptPaths.contains(requestPath)) {
      val keyStr = Option(request.getHeader("X-Client-Key")).map(_.trim).getOrElse("")
      val keyOpt = Try(UUID.fromString(keyStr)).toOption
      keyOpt match {
        case None =>
          halt(Unauthorized(Map("error" -> "missing or malformed X-Client-Key")))
        case Some(uuid) =>
          appClientRepository.isActive(uuid) match {
            case Right(true) =>
              appClientRepository.markSeen(uuid)
            case Right(false) =>
              halt(Unauthorized(Map("error" -> "unknown or revoked X-Client-Key")))
            case Left(e) =>
              logger.error(s"app_client lookup failed for $uuid: ${e.getMessage}", e)
              halt(InternalServerError(Map("error" -> "client lookup failed")))
          }
      }
    }
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  private def clientIdFromHeader: Option[UUID] =
    Option(request.getHeader("X-Client-Key"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(s => Try(UUID.fromString(s)).toOption)

  // ─────────────────────────── Client registry ───────────────────────────

  /** Bootstrap. The Android app generates a UUID once, stores it in
    * encrypted prefs, and calls this on first launch. We record it (with
    * platform='android') so subsequent requests' X-Client-Key lookups
    * succeed. */
  post("/clients/register") {
    try {
      val req = parsedBody.extract[RegisterClientRequest]
      val parsed = Try(UUID.fromString(req.clientId.trim)).toOption
      parsed match {
        case None =>
          BadRequest(Map("error" -> "clientId must be a UUID"))
        case _ if req.bundleId.trim.isEmpty =>
          BadRequest(Map("error" -> "bundleId is required"))
        case Some(uuid) =>
          appClientRepository.upsertOnRegister(
            clientId  = uuid,
            bundleId  = req.bundleId.trim,
            osVersion = req.osVersion.map(_.trim).filter(_.nonEmpty),
            platform  = "android"
          ) match {
            case Right(_) => Map("ok" -> true)
            case Left(e) =>
              InternalServerError(Map("error" -> s"Failed to register client: ${e.getMessage}"))
          }
      }
    } catch {
      case e: MappingException =>
        BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
    }
  }

  // ───────────────────────────── Notifications ───────────────────────────

  /** Record / refresh an FCM token. Authorization (Firebase ID token) is
    * optional — when present we link the row to user_id, when absent the
    * row stays anonymous. Symmetric to iOS's POST /v2/notifications/subscribe.
    */
  post("/notifications/subscribe") {
    try {
      val req = parsedBody.extract[AndroidSubscribeRequest]
      if (req.deviceId.trim.isEmpty || req.fcmToken.trim.isEmpty) {
        BadRequest(Map("error" -> "deviceId and fcmToken are required"))
      } else if (req.frequency < 1 || req.frequency > 4) {
        BadRequest(Map("error" -> "frequency must be between 1 and 4"))
      } else {
        val userIdOpt: Option[String] =
          Option(request.getHeader("Authorization")).filter(_.nonEmpty) match {
            case None => None
            case Some(header) =>
              firebaseVerifier.verify(header) match {
                case Right(uid) => Some(uid)
                case Left(e) =>
                  logger.warn(s"subscribe: token verification failed: ${e.getMessage}")
                  halt(Unauthorized(Map("error" -> "invalid or expired token")))
              }
          }
        androidNotificationService.subscribe(req, userIdOpt, clientIdFromHeader) match {
          case Right(_) => Map("ok" -> true)
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to subscribe: ${e.getMessage}"))
        }
      }
    } catch {
      case e: MappingException =>
        BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
    }
  }

  delete("/notifications/:deviceId") {
    val deviceId = params("deviceId").trim
    if (deviceId.isEmpty) {
      BadRequest(Map("error" -> "deviceId is required"))
    } else {
      androidNotificationService.deleteDevice(deviceId) match {
        case Right(rows) if rows > 0 => NoContent()
        case Right(_)                => NotFound(Map("error" -> "no subscription for that deviceId"))
        case Left(e) =>
          InternalServerError(Map("error" -> s"Failed to delete subscription: ${e.getMessage}"))
      }
    }
  }
}
