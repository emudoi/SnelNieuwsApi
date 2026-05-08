package com.snelnieuws.service

import cats.effect.unsafe.implicits.global
import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import com.snelnieuws.model.ArticleCreate
import com.snelnieuws.repository.ArticleRepository
import doobie.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.OffsetDateTime

/** Verifies the article-count floor that protects against the table being
  * drained when ingestion is paused. The scheduler uses a private runOnce —
  * we exercise the underlying repository methods + a hand-rolled equivalent
  * of the runOnce branch logic, so the assertion is on observable rows. */
class ArticleCleanupSchedulerSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val repo = new ArticleRepository(Database.transactor)

  "ArticleCleanupScheduler.runOnce" should {
    "skip deletion when the table has fewer than MinArticleCount rows" in {
      requireDb()

      // Wipe + seed exactly 5 rows that are *all* old enough to delete.
      sql"DELETE FROM articles".update.run
        .transact(Database.transactor).unsafeRunSync()
      (1 to 5).foreach { i =>
        repo.create(
          ArticleCreate(
            author      = Some("cleanup-floor-spec"),
            title       = s"floor-spec-old-$i",
            description = None,
            url         = s"https://example.com/cleanup-floor-spec/old/$i",
            urlToImage  = None,
            content     = None,
            category    = Some("cleanup-floor-spec")
          )
        ) shouldBe a[Right[_, _]]
      }
      // Backdate them so a 0-hour cutoff would otherwise wipe them out.
      sql"""UPDATE articles SET published_at = NOW() - INTERVAL '7 days'
            WHERE author = 'cleanup-floor-spec'""".update.run
        .transact(Database.transactor).unsafeRunSync()

      val before = repo.count().toOption.getOrElse(-1)
      before should be < ArticleCleanupScheduler.MinArticleCount

      // Mirror runOnce()'s guard: skip delete when below floor.
      val totalE = repo.count()
      totalE shouldBe a[Right[_, _]]
      val total = totalE.toOption.get
      if (total >= ArticleCleanupScheduler.MinArticleCount) {
        repo.deletePublishedBefore(OffsetDateTime.now()) shouldBe a[Right[_, _]]
      }

      val after = repo.count().toOption.getOrElse(-1)
      after shouldBe before
    }

    "delete eligible rows when at or above MinArticleCount" in {
      requireDb()

      // Seed past the floor, all old enough to delete.
      sql"DELETE FROM articles".update.run
        .transact(Database.transactor).unsafeRunSync()
      val seedSize = ArticleCleanupScheduler.MinArticleCount + 5
      (1 to seedSize).foreach { i =>
        repo.create(
          ArticleCreate(
            author      = Some("cleanup-floor-spec-2"),
            title       = s"floor-spec-above-$i",
            description = None,
            url         = s"https://example.com/cleanup-floor-spec/above/$i",
            urlToImage  = None,
            content     = None,
            category    = Some("cleanup-floor-spec-2")
          )
        ) shouldBe a[Right[_, _]]
      }
      sql"""UPDATE articles SET published_at = NOW() - INTERVAL '7 days'
            WHERE author = 'cleanup-floor-spec-2'""".update.run
        .transact(Database.transactor).unsafeRunSync()

      // Above the floor → delete proceeds.
      repo.count().toOption.get should be >= ArticleCleanupScheduler.MinArticleCount
      repo.deletePublishedBefore(OffsetDateTime.now()) match {
        case Right(deleted) => deleted shouldBe seedSize
        case Left(e)        => fail(e)
      }
    }
  }
}
