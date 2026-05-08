package com.snelnieuws.api

import com.snelnieuws.service.{DispatchOutcome, NotificationService}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

/** Mounted at the exact path `/notifications/dispatch`. Auth is unchanged
  * from when this lived inside NewsServlet: the caller (Airflow) presents
  * `X-API-Key`, no Firebase token, no v2 client gate. Behavior is byte-
  * for-byte the same — only the carrier servlet has changed.
  *
  * Kept separate from v2 deliberately: bringing dispatch under the v2
  * gate would force Airflow to also send X-Client + X-Client-Key, and
  * we have no reason to couple internal-cron auth with mobile-app auth.
  */
class NotificationDispatchServlet(
  notificationService: NotificationService,
  notificationsApiKey: String
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[NotificationDispatchServlet])

  before() {
    contentType = formats("json")
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  // The mount is exact (`/notifications/dispatch`), so the servlet sees
  // the request as path-info "/" — that's what this route matches.
  post("/") {
    val provided = Option(request.getHeader("X-API-Key")).getOrElse("")
    if (notificationsApiKey.isEmpty || provided != notificationsApiKey) {
      Unauthorized(Map("error" -> "invalid or missing X-API-Key"))
    } else {
      val rawFrequency = params.get("frequency")
      val frequencyOpt = rawFrequency.flatMap(_.toIntOption)
      if (rawFrequency.isDefined && frequencyOpt.isEmpty) {
        BadRequest(Map("error" -> "frequency must be a number"))
      } else if (frequencyOpt.exists(f => f < 1 || f > 4)) {
        BadRequest(Map("error" -> "frequency must be between 1 and 4"))
      } else {
        notificationService.dispatch(frequencyOpt) match {
          case Right(DispatchOutcome.Sent(response)) => response
          case Right(DispatchOutcome.Disabled) =>
            ServiceUnavailable(Map("error" -> "notifications disabled"))
          case Left(e) =>
            InternalServerError(Map("error" -> s"Failed to dispatch: ${e.getMessage}"))
        }
      }
    }
  }
}
