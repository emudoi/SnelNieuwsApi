package com.snelnieuws.service

import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.{SimpleApnsPushNotification, TokenUtil}
import com.eatthepath.pushy.apns.{ApnsClient, ApnsClientBuilder, PushNotificationResponse}
import com.snelnieuws.db.NotificationSubscriptionRepository
import org.slf4j.LoggerFactory

import java.io.File
import java.util.concurrent.CompletableFuture
import scala.jdk.OptionConverters._

object ApnsMessagingService {

  private val logger                   = LoggerFactory.getLogger(getClass)
  private var clientOpt: Option[ApnsClient] = None
  private var bundleId: String         = ""

  /** Builds and caches an APNs HTTP/2 client, authenticated via JWT signed
   *  with the provided .p8 key. Idempotent — safe to call more than once.
   *  `sandbox=true` targets api.sandbox.push.apple.com (Debug iOS builds);
   *  false targets api.push.apple.com (TestFlight + App Store).
   */
  def init(keyPath: String, keyId: String, teamId: String, bundle: String, sandbox: Boolean): Unit = synchronized {
    if (clientOpt.isDefined) return
    val host       = if (sandbox) ApnsClientBuilder.DEVELOPMENT_APNS_HOST else ApnsClientBuilder.PRODUCTION_APNS_HOST
    val signingKey = ApnsSigningKey.loadFromPkcs8File(new File(keyPath), teamId, keyId)
    val client     = new ApnsClientBuilder()
      .setApnsServer(host)
      .setSigningKey(signingKey)
      .build()
    clientOpt = Some(client)
    bundleId  = bundle
    logger.info(s"APNs client initialized (sandbox=$sandbox, bundle=$bundle, keyId=$keyId)")
  }

  def isInitialized: Boolean = clientOpt.isDefined

  /** Sends an alert push to every device token in parallel and returns
   *  (sent, failed). Tokens APNs reports as `Unregistered` or
   *  `BadDeviceToken` are removed from `notification_subscriptions`.
   */
  def sendBatch(tokens: List[String], title: String, body: String): (Int, Int) = {
    if (tokens.isEmpty) return (0, 0)
    val client = clientOpt.getOrElse {
      logger.warn("APNs send requested but client not initialized — skipping")
      return (0, tokens.size)
    }
    val payload = buildPayload(title, body)

    val futures: List[(String, CompletableFuture[PushNotificationResponse[SimpleApnsPushNotification]])] =
      tokens.map { token =>
        val sanitized    = TokenUtil.sanitizeTokenString(token)
        val notification = new SimpleApnsPushNotification(sanitized, bundleId, payload)
        (token, client.sendNotification(notification))
      }

    var sent   = 0
    var failed = 0
    futures.foreach { case (token, fut) =>
      try {
        val resp = fut.get()
        if (resp.isAccepted) {
          sent += 1
        } else {
          failed += 1
          val reason = resp.getRejectionReason.toScala.getOrElse("unknown")
          if (reason == "Unregistered" || reason == "BadDeviceToken") {
            logger.info(s"Pruning rejected token (reason=$reason)")
            try NotificationSubscriptionRepository.deleteByApnsToken(token)
            catch {
              case e: Exception =>
                logger.warn(s"Failed to delete dead token: ${e.getMessage}")
            }
          } else {
            logger.warn(s"APNs rejected token: reason=$reason")
          }
        }
      } catch {
        case e: Exception =>
          failed += 1
          logger.warn(s"APNs send failed: ${e.getMessage}")
      }
    }

    (sent, failed)
  }

  private def buildPayload(title: String, body: String): String =
    s"""{"aps":{"alert":{"title":"${escape(title)}","body":"${escape(body)}"},"sound":"default"}}"""

  private def escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}
