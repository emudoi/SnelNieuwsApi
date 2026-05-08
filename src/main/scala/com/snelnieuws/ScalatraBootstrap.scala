package com.snelnieuws

import com.snelnieuws.db.Database
import org.scalatra.LifeCycle
import org.slf4j.LoggerFactory

import javax.servlet.ServletContext
import java.util.concurrent.atomic.AtomicReference

class ScalatraBootstrap extends LifeCycle {

  private val logger        = LoggerFactory.getLogger(classOf[ScalatraBootstrap])
  private val componentsRef = new AtomicReference[Option[Components]](None)

  override def init(context: ServletContext): Unit = {
    logger.info("Initializing snel-nieuws-api servlets...")

    try
      Database.migrate()
    catch {
      case e: Exception =>
        logger.error("Database migration failed — cannot start without tables", e)
        throw e
    }

    val components = Components.default()
    componentsRef.set(Some(components))

    context.mount(components.healthServlet, "/health/*")
    // Exact-path mounts win over the v1 catch-all `/*` regardless of
    // declaration order (Servlet API spec). Each of the routes below was
    // previously a Scalatra route inside NewsServlet; extracting them into
    // their own servlets is a no-op for callers — same URLs, same auth.
    context.mount(components.notificationDispatchServlet, "/notifications/dispatch")
    context.mount(components.staticContentServlet, "/privacy")
    context.mount(components.staticContentServlet, "/support")
    context.mount(components.newsServletV2, "/v2/*")
    // v1 catch-all stays mounted last, conceptually — kept here for
    // readability of the routing table.
    context.mount(components.newsServlet, "/*")

    components.startBackgroundWorkers()

    logger.info("snel-nieuws-api servlets initialized successfully")
  }

  override def destroy(context: ServletContext): Unit = {
    componentsRef.get().foreach(_.close())
    super.destroy(context)
  }
}
