package com.snelnieuws.db

import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._

object NotificationSubscriptionRepository {
  private val xa = Database.transactor

  def upsert(deviceId: String, apnsToken: String, frequency: Int): Int = {
    sql"""
      INSERT INTO notification_subscriptions (device_id, apns_token, frequency)
      VALUES ($deviceId, $apnsToken, $frequency)
      ON CONFLICT (device_id) DO UPDATE SET
        apns_token = EXCLUDED.apns_token,
        frequency  = EXCLUDED.frequency,
        updated_at = NOW()
    """.update.run.transact(xa).unsafeRunSync()
  }

  def findTokensByFrequency(frequency: Int): List[String] = {
    sql"""
      SELECT apns_token FROM notification_subscriptions
      WHERE frequency = $frequency
    """.query[String].to[List].transact(xa).unsafeRunSync()
  }

  def findAllTokens(): List[String] = {
    sql"""
      SELECT apns_token FROM notification_subscriptions
    """.query[String].to[List].transact(xa).unsafeRunSync()
  }

  def deleteByApnsToken(token: String): Int = {
    sql"DELETE FROM notification_subscriptions WHERE apns_token = $token"
      .update.run.transact(xa).unsafeRunSync()
  }

  def deleteByDeviceId(deviceId: String): Int = {
    sql"DELETE FROM notification_subscriptions WHERE device_id = $deviceId"
      .update.run.transact(xa).unsafeRunSync()
  }
}
