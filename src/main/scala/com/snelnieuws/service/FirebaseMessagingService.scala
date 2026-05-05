package com.snelnieuws.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.messaging._
import com.google.firebase.{FirebaseApp, FirebaseOptions}
import com.snelnieuws.db.NotificationSubscriptionRepository
import org.slf4j.LoggerFactory

import java.io.FileInputStream
import scala.jdk.CollectionConverters._

object FirebaseMessagingService {

  private val logger      = LoggerFactory.getLogger(getClass)
  private var initialized = false

  /** Initializes the Firebase Admin SDK from a service account JSON on disk.
   *  Idempotent — safe to call more than once.
   */
  def init(credentialsPath: String): Unit = synchronized {
    if (initialized) return
    val stream = new FileInputStream(credentialsPath)
    try {
      val options = FirebaseOptions
        .builder()
        .setCredentials(GoogleCredentials.fromStream(stream))
        .build()
      FirebaseApp.initializeApp(options)
      initialized = true
      logger.info(s"Firebase Admin SDK initialized from $credentialsPath")
    } finally {
      stream.close()
    }
  }

  def isInitialized: Boolean = initialized

  /** Sends a notification to all provided FCM tokens via FCM multicast (chunked at 500).
   *  Tokens that FCM rejects as `UNREGISTERED` are removed from
   *  `notification_subscriptions`. Returns (sent, failed).
   */
  def sendBatch(
    tokens: List[String],
    title: String,
    body: String,
    data: Map[String, String]
  ): (Int, Int) = {
    if (tokens.isEmpty) return (0, 0)
    if (!initialized) {
      logger.warn("FCM send requested but Firebase Admin SDK not initialized — skipping")
      return (0, tokens.size)
    }

    var sent   = 0
    var failed = 0

    tokens.grouped(500).foreach { chunk =>
      val notification = Notification.builder().setTitle(title).setBody(body).build()
      val multicast = MulticastMessage
        .builder()
        .setNotification(notification)
        .putAllData(data.asJava)
        .addAllTokens(chunk.asJava)
        .build()

      try {
        val response: BatchResponse =
          FirebaseMessaging.getInstance().sendEachForMulticast(multicast)
        sent += response.getSuccessCount
        failed += response.getFailureCount

        val deadTokens = chunk.zip(response.getResponses.asScala.toList).flatMap {
          case (token, sendResponse) if !sendResponse.isSuccessful =>
            val ex   = sendResponse.getException
            val code = Option(ex).flatMap(e => Option(e.getMessagingErrorCode))
            if (code.contains(MessagingErrorCode.UNREGISTERED)) {
              Some(token)
            } else {
              logger.warn(
                s"FCM send failed for token (non-fatal): code=${code.orNull} message=${Option(ex).map(_.getMessage).orNull}"
              )
              None
            }
          case _ => None
        }

        if (deadTokens.nonEmpty) {
          logger.info(s"Removing ${deadTokens.size} unregistered token(s) from notification_subscriptions")
          deadTokens.foreach { token =>
            try NotificationSubscriptionRepository.deleteByFcmToken(token)
            catch {
              case e: Exception =>
                logger.warn(s"Failed to delete dead token: ${e.getMessage}")
            }
          }
        }
      } catch {
        case e: Exception =>
          logger.error(s"FCM multicast failed for chunk of ${chunk.size}: ${e.getMessage}", e)
          failed += chunk.size
      }
    }

    (sent, failed)
  }
}
