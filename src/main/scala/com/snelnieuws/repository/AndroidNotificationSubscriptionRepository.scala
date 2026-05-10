package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.util.UUID

/** Mirror of `NotificationSubscriptionRepository` for the Android FCM stack.
  *
  * Kept fully separate from the iOS APNs subscription repo so iOS query
  * shapes and table layout are untouched. The two stacks share nothing
  * except the `articles` table they both read.
  *
  * Unlike iOS, there is no environment column — FCM has a single endpoint
  * for both debug and release builds.
  */
class AndroidNotificationSubscriptionRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[AndroidNotificationSubscriptionRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Upsert the device's subscription row. Mirrors the iOS upsert behavior
    * around `client_id`: COALESCE preserves an existing client_id if a
    * later subscribe call doesn't carry one (e.g. after a logout flow).
    */
  def upsert(
    deviceId: String,
    fcmToken: String,
    frequency: Int,
    userId: Option[String] = None,
    clientId: Option[UUID] = None
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO android_notification_subscriptions
            (device_id, fcm_token, frequency, user_id, client_id)
          VALUES ($deviceId, $fcmToken, $frequency, $userId, $clientId)
          ON CONFLICT (device_id) DO UPDATE SET
            fcm_token  = EXCLUDED.fcm_token,
            frequency  = EXCLUDED.frequency,
            user_id    = EXCLUDED.user_id,
            client_id  = COALESCE(EXCLUDED.client_id, android_notification_subscriptions.client_id),
            updated_at = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert Android subscription deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
    }

  def findTokensByFrequency(frequency: Int): Either[Throwable, List[String]] =
    try
      Right(
        sql"""
          SELECT fcm_token FROM android_notification_subscriptions
          WHERE frequency = $frequency
        """.query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to fetch Android tokens for frequency=$frequency: ${e.getMessage}", e)
        Left(e)
    }

  def findAllTokens(): Either[Throwable, List[String]] =
    try
      Right(
        sql"SELECT fcm_token FROM android_notification_subscriptions"
          .query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to fetch all Android subscription tokens: ${e.getMessage}", e)
        Left(e)
    }

  def deleteByFcmToken(token: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM android_notification_subscriptions WHERE fcm_token = $token"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete Android subscription by fcmToken: ${e.getMessage}", e)
        Left(e)
    }

  def deleteByDeviceId(deviceId: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM android_notification_subscriptions WHERE device_id = $deviceId"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete Android subscription deviceId=$deviceId: ${e.getMessage}", e)
        Left(e)
    }
}
