package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.util.UUID

class AppClientRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[AppClientRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Idempotent upsert. iOS calls register on every cold start when its
    * Keychain UUID hasn't been confirmed-as-registered yet, so duplicates
    * are expected — we just refresh os_version + last_seen_at. We
    * deliberately do NOT touch revoked_at here: a re-register from a
    * client that an operator already flagged would otherwise silently
    * un-revoke itself, defeating the only kill-switch we have. Once
    * revoked, only manual SQL un-revokes. */
  def upsertOnRegister(
    clientId: UUID,
    bundleId: String,
    osVersion: Option[String],
    platform: String
  ): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO app_clients (client_id, bundle_id, os_version, platform)
          VALUES ($clientId, $bundleId, $osVersion, $platform)
          ON CONFLICT (client_id) DO UPDATE SET
            bundle_id    = EXCLUDED.bundle_id,
            os_version   = EXCLUDED.os_version,
            platform     = EXCLUDED.platform,
            last_seen_at = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert app_client clientId=$clientId: ${e.getMessage}", e)
        Left(e)
    }

  /** Returns true iff the client_id exists and has not been revoked.
    * Used by the v2 before() filter on every request — keep cheap. */
  def isActive(clientId: UUID): Either[Throwable, Boolean] =
    try
      Right(
        sql"""
          SELECT 1 FROM app_clients
          WHERE client_id = $clientId AND revoked_at IS NULL
          LIMIT 1
        """.query[Int].option.transact(transactor).unsafeRunSync().isDefined
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to look up app_client clientId=$clientId: ${e.getMessage}", e)
        Left(e)
    }

  /** Bumps last_seen_at. Called from the v2 filter on the request thread
    * when we want to track liveness. Best-effort: a failure here mustn't
    * fail the request. */
  def markSeen(clientId: UUID): Either[Throwable, Int] =
    try
      Right(
        sql"""
          UPDATE app_clients SET last_seen_at = NOW()
          WHERE client_id = $clientId AND revoked_at IS NULL
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to bump last_seen_at clientId=$clientId: ${e.getMessage}", e)
        Left(e)
    }
}
