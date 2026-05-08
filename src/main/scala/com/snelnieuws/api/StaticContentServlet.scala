package com.snelnieuws.api

import org.scalatra._
import org.slf4j.LoggerFactory

/** Serves the public static HTML pages — privacy and support. Mounted at
  * each exact path (`/privacy`, `/support`); the route below switches on
  * `request.getServletPath` to pick the right resource. No auth, no JSON,
  * no v2 gate. These pages are linked from the App Store listing and from
  * an in-app `Link(...)` (which opens in Safari, no headers attached) so
  * gating them would just break the link.
  */
class StaticContentServlet extends ScalatraServlet {

  private val logger = LoggerFactory.getLogger(classOf[StaticContentServlet])

  private val PathToResource: Map[String, String] = Map(
    "/privacy" -> "static/privacy.html",
    "/support" -> "static/support.html"
  )

  // Single route that fires for whichever exact path this servlet was
  // mounted at — `getServletPath` returns the mount, e.g. "/privacy".
  get("/") {
    val mount = Option(request.getServletPath).getOrElse("")
    PathToResource.get(mount) match {
      case Some(resourcePath) => serveStatic(resourcePath)
      case None =>
        logger.warn(s"StaticContentServlet hit at unexpected mount: $mount")
        NotFound(Map("error" -> "not found"))
    }
  }

  private def serveStatic(resourcePath: String): Any = {
    Option(getClass.getClassLoader.getResourceAsStream(resourcePath)) match {
      case Some(stream) =>
        try {
          val bytes = stream.readAllBytes()
          contentType = "text/html; charset=utf-8"
          response.setHeader("Cache-Control", "public, max-age=3600")
          new String(bytes, "UTF-8")
        } finally {
          stream.close()
        }
      case None =>
        NotFound(Map("error" -> s"Resource $resourcePath not found"))
    }
  }
}
