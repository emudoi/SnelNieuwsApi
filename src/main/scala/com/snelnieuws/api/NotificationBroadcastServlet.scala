package com.snelnieuws.api

import com.snelnieuws.model.BroadcastRequest
import com.snelnieuws.service.NotificationService
import org.json4s.{DefaultFormats, Formats, MappingException}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

/** Mounted at the exact path `/notifications/broadcast`. Auth is the same
  * shared X-API-Key the dispatch servlets use. The endpoint forwards a
  * caller-supplied `text` to each environment whose feature flag is on
  * (`test_notification` → sandbox, `notify_applestore_apps` → production).
  *
  * Independent of the per-frequency dispatch endpoints — does not read or
  * write `notification_dispatches`. Title is hardcoded ("Snel Nieuws"); only
  * the body varies per call.
  */
class NotificationBroadcastServlet(
  notificationService: NotificationService,
  notificationsApiKey: String
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[NotificationBroadcastServlet])

  before() {
    contentType = formats("json")
  }

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  post("/") {
    val provided = Option(request.getHeader("X-API-Key")).getOrElse("")
    if (notificationsApiKey.isEmpty || provided != notificationsApiKey) {
      Unauthorized(Map("error" -> "invalid or missing X-API-Key"))
    } else {
      try {
        val req = parsedBody.extract[BroadcastRequest]
        if (req.text.trim.isEmpty) {
          BadRequest(Map("error" -> "text is required"))
        } else {
          notificationService.broadcast(req.text) match {
            case Right(response) => response
            case Left(e) =>
              InternalServerError(Map("error" -> s"Failed to broadcast: ${e.getMessage}"))
          }
        }
      } catch {
        case e: MappingException =>
          BadRequest(Map("error" -> s"Invalid request body: ${e.getMessage}"))
      }
    }
  }
}
