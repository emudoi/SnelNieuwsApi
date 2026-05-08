package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.User
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

class UserRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[UserRepository])

  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** Idempotent upsert keyed on Firebase uid. Updates email + updated_at on
    * conflict so a user changing their email in Firebase is reflected here
    * on the next signup/login backfill call. */
  def upsert(uid: String, email: String): Either[Throwable, Int] =
    try
      Right(
        sql"""
          INSERT INTO users (id, email)
          VALUES ($uid, $email)
          ON CONFLICT (id) DO UPDATE SET
            email      = EXCLUDED.email,
            updated_at = NOW()
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to upsert user uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  def findById(uid: String): Either[Throwable, Option[User]] =
    try
      Right(
        sql"""
          SELECT id, email, created_at::TEXT, updated_at::TEXT
          FROM users WHERE id = $uid
        """.query[User].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to find user uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  /** Returns the user's saved category list, or None when the row has
    * never been written (NULL column). Empty list (a deliberately-saved
    * empty array) returns Some(Nil) — caller can disambiguate. */
  def findCategories(uid: String): Either[Throwable, Option[List[String]]] =
    try
      Right(
        sql"""
          SELECT selected_categories FROM users WHERE id = $uid
        """.query[Option[List[String]]].option
          .transact(transactor).unsafeRunSync().flatten match {
            case Some(arr) => Some(arr)
            case None      => None
          }
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load categories uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  /** Overwrites `selected_categories` with the provided list. The list
    * is stored as-is (callers are expected to validate and order it
    * before calling — the route layer does both). Returns rows affected
    * (1 if user exists, 0 if not — caller can decide whether to upsert). */
  def saveCategories(uid: String, categories: List[String]): Either[Throwable, Int] =
    try
      Right(
        sql"""
          UPDATE users SET
            selected_categories = $categories,
            updated_at          = NOW()
          WHERE id = $uid
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to save categories uid=$uid: ${e.getMessage}", e)
        Left(e)
    }

  /** Cascades to notification_subscriptions via FK ON DELETE CASCADE. */
  def deleteById(uid: String): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM users WHERE id = $uid".update.run
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete user uid=$uid: ${e.getMessage}", e)
        Left(e)
    }
}
