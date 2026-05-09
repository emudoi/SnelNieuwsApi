package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.slf4j.LoggerFactory

class FeatureFlagRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[FeatureFlagRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Read a single flag's current state. An unknown feature name returns
    * false ("fail closed") rather than raising — a typo in code can only
    * silence a broadcast, not accidentally enable one. */
  def isEnabled(feature: String): Either[Throwable, Boolean] =
    try
      Right(
        sql"SELECT is_enabled FROM feature_flags WHERE feature = $feature"
          .query[Boolean].option.transact(transactor).unsafeRunSync().getOrElse(false)
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to read feature flag $feature: ${e.getMessage}", e)
        Left(e)
    }

  /** Upsert a flag's value. Used by tests to flip flags before exercising
    * broadcast paths. Production toggling is expected to be a manual
    * `UPDATE feature_flags ...` via psql for now. */
  def setEnabled(feature: String, enabled: Boolean): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO feature_flags (feature, is_enabled)
          VALUES ($feature, $enabled)
          ON CONFLICT (feature) DO UPDATE SET is_enabled = EXCLUDED.is_enabled
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to set feature flag $feature=$enabled: ${e.getMessage}", e)
        Left(e)
    }
}
