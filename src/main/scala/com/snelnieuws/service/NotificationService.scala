package com.snelnieuws.service

import com.snelnieuws.model.{
  BroadcastEnvResult,
  BroadcastResponse,
  DispatchResponse,
  SubscribeRequest
}
import com.snelnieuws.repository.{
  ArticleRepository,
  FeatureFlagRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository
}
import org.slf4j.LoggerFactory

import java.util.UUID

sealed trait DispatchOutcome
object DispatchOutcome {
  case object Disabled                          extends DispatchOutcome
  case class Sent(response: DispatchResponse)   extends DispatchOutcome
}

object NotificationEnvironment {
  val Production = "production"
  val Sandbox    = "sandbox"
}

/** Names of the feature flags read by NotificationService.broadcast. The
  * constants exist so a typo in code is a compile error rather than a
  * silently-disabled broadcast (the repo treats unknown names as `false`).
  */
object BroadcastFeatureFlag {
  val Sandbox    = "test_notification"
  val Production = "notify_applestore_apps"
}

/** Owns the subscribe + dispatch flows. APNs is optional per environment —
 *  when the matching client is None (notifications disabled, init failed,
 *  or that environment was never configured), dispatch returns
 *  `DispatchOutcome.Disabled` and the servlet maps that to 503.
 *
 *  Two clients exist because APNs sandbox tokens (Xcode-debug builds) only
 *  work against api.sandbox.push.apple.com and production tokens (TestFlight
 *  + App Store) only work against api.push.apple.com. Same .p8 signs both
 *  — they differ only in which Apple host they target.
 */
class NotificationService(
  articleRepository: ArticleRepository,
  subscriptionRepository: NotificationSubscriptionRepository,
  dispatchRepository: NotificationDispatchRepository,
  featureFlagRepository: FeatureFlagRepository,
  apnsProd: Option[ApnsMessagingService],
  apnsSandbox: Option[ApnsMessagingService]
) {

  private val logger = LoggerFactory.getLogger(classOf[NotificationService])

  def subscribe(
    req: SubscribeRequest,
    userId: Option[String] = None,
    clientId: Option[UUID] = None
  ): Either[Throwable, Int] =
    subscriptionRepository.upsert(
      req.deviceId,
      req.apnsToken,
      req.frequency,
      req.environment,
      userId,
      clientId
    )

  /** Delete a single device's subscription regardless of whether it was
    * linked to a user. Used by account-deletion to clean up rows whose
    * user_id is NULL (the FK CASCADE doesn't cover those). */
  def deleteDevice(deviceId: String): Either[Throwable, Int] =
    subscriptionRepository.deleteByDeviceId(deviceId)

  def dispatch(
    frequency: Option[Int],
    environment: String
  ): Either[Throwable, DispatchOutcome] = {
    val client = environment match {
      case NotificationEnvironment.Sandbox => apnsSandbox
      case _                               => apnsProd
    }
    client match {
      case None =>
        Right(DispatchOutcome.Disabled)
      case Some(c) =>
        for {
          lastAsOf    <- dispatchRepository.findLastAsOfArticleId(frequency, environment)
          newArticles <- articleRepository.countSinceId(lastAsOf)
          currentMax  <- articleRepository.latestId()
          title        = if (newArticles == 1) "1 new article" else s"$newArticles new articles"
          body         = "Check them out in Snel Nieuws"
          sentFailed  <- sendIfAny(c, frequency, environment, newArticles, title, body)
          (sent, failed) = sentFailed
          _           <- dispatchRepository.recordDispatch(
            frequency = frequency,
            environment = environment,
            asOfArticleId = currentMax,
            newArticles = newArticles,
            sent = sent,
            failed = failed,
            title = title,
            body = body
          )
        } yield DispatchOutcome.Sent(DispatchResponse(sent, failed, newArticles))
    }
  }

  private def sendIfAny(
    client: ApnsMessagingService,
    frequency: Option[Int],
    environment: String,
    newArticles: Int,
    title: String,
    body: String
  ): Either[Throwable, (Int, Int)] = {
    if (newArticles == 0) Right((0, 0))
    else {
      val tokensE = frequency match {
        case Some(f) => subscriptionRepository.findTokensByFrequencyAndEnvironment(f, environment)
        case None    => subscriptionRepository.findAllTokensByEnvironment(environment)
      }
      tokensE.map(tokens => client.sendBatch(tokens, title, body))
    }
  }

  /** Broadcast a free-form text to every subscriber in each environment
    * whose feature flag is enabled. Independent of the per-frequency
    * dispatch tracking — does not read or write `notification_dispatches`.
    *
    * Title is hardcoded to "Snel Nieuws"; the request body becomes the
    * APNs alert body. Both flags can be enabled simultaneously, in which
    * case the broadcast fans out to both environments in a single call.
    */
  def broadcast(text: String): Either[Throwable, BroadcastResponse] = {
    val title = "Snel Nieuws"
    for {
      sandboxEnabled <- featureFlagRepository.isEnabled(BroadcastFeatureFlag.Sandbox)
      prodEnabled    <- featureFlagRepository.isEnabled(BroadcastFeatureFlag.Production)
      sandbox        <- broadcastTo(apnsSandbox, sandboxEnabled, NotificationEnvironment.Sandbox, title, text)
      production     <- broadcastTo(apnsProd, prodEnabled, NotificationEnvironment.Production, title, text)
    } yield BroadcastResponse(production = production, sandbox = sandbox)
  }

  private def broadcastTo(
    client: Option[ApnsMessagingService],
    enabled: Boolean,
    environment: String,
    title: String,
    body: String
  ): Either[Throwable, BroadcastEnvResult] = {
    if (!enabled) Right(BroadcastEnvResult(enabled = false, sent = 0, failed = 0))
    else
      client match {
        case None =>
          // Flag is on but APNs init never succeeded for this environment
          // (e.g. .p8 missing at boot). Surface enabled=true so the caller
          // can tell the flag is on; sent/failed=0 signals nothing went
          // out. The pod logs explain why.
          logger.warn(s"broadcast: $environment flag is on but APNs client is not initialized")
          Right(BroadcastEnvResult(enabled = true, sent = 0, failed = 0))
        case Some(c) =>
          subscriptionRepository.findAllTokensByEnvironment(environment).map { tokens =>
            val (sent, failed) = c.sendBatch(tokens, title, body)
            BroadcastEnvResult(enabled = true, sent = sent, failed = failed)
          }
      }
  }
}
