package com.snelnieuws.service

import com.snelnieuws.model.{DispatchResponse, SubscribeRequest}
import com.snelnieuws.repository.{
  ArticleRepository,
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
}
