package com.snelnieuws.repository

import cats.effect.unsafe.implicits.global
import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import com.snelnieuws.model.ArticleCreate
import doobie.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

/** Coverage for the wide-pool read methods that the personalised-feed
  * filter relies on. The pre-existing findAll / findByCategory /
  * findByCategories are covered indirectly by NewsServletV2Spec and
  * ArticleServiceSpec; this spec just locks in the *Pool variants.
  */
class ArticleRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val repo = new ArticleRepository(Database.transactor)

  // Local marker so we can scope cleanup + assertions to rows we created
  // and not collide with other suites' seeded data.
  private val tag = s"article-repo-spec-${UUID.randomUUID().toString.take(8)}"

  private def wipeOurRows(): Unit = {
    sql"DELETE FROM articles WHERE author = $tag"
      .update.run.transact(Database.transactor).unsafeRunSync()
  }

  private def seed(n: Int, category: Option[String] = None): List[Long] =
    (1 to n).toList.map { i =>
      val cat = category.orElse(Some("technology"))
      repo.create(ArticleCreate(
        author      = Some(tag),
        title       = s"$tag-$i-${UUID.randomUUID()}",
        description = None,
        url         = s"https://example.com/$tag/$i",
        urlToImage  = None,
        content     = None,
        category    = cat
      )).toOption.get.id
    }

  "ArticleRepository.findAllPool" should {
    "respect the requested limit" in {
      requireDb()
      wipeOurRows()
      seed(10)
      repo.findAllPool(limit = 5)
        .toOption.get.count(_.author.contains(tag)) shouldBe 5
    }

    "default to 300 rows" in {
      requireDb()
      wipeOurRows()
      seed(310)
      // Don't assert exact length 300 (other tests' rows are in the table
      // too); just confirm our rows fit and the cap is at-or-above 300.
      val all = repo.findAllPool()
        .toOption.get
      all.length should be <= 300
      all.length should be >= 300.min(310)
    }

    "order by published_at DESC" in {
      requireDb()
      wipeOurRows()
      seed(5)
      val rows = repo.findAllPool(limit = 50)
        .toOption.get
        .filter(_.author.contains(tag))
      val publishedTimes = rows.map(_.publishedAt)
      publishedTimes shouldBe publishedTimes.sorted.reverse
    }
  }

  "ArticleRepository.findByCategoryPool" should {
    "filter to one category" in {
      requireDb()
      wipeOurRows()
      seed(3, Some("politics"))
      seed(2, Some("sports"))
      val politics = repo.findByCategoryPool("politics", limit = 50)
        .toOption.get
        .filter(_.author.contains(tag))
      politics.length shouldBe 3
      politics.foreach(_.category shouldBe Some("politics"))
    }

    "be case-insensitive" in {
      requireDb()
      wipeOurRows()
      seed(2, Some("politics"))
      val byUpper = repo.findByCategoryPool("POLITICS", limit = 50)
        .toOption.get
        .filter(_.author.contains(tag))
      byUpper.length shouldBe 2
    }
  }

  "ArticleRepository.findByCategoriesPool" should {
    "union multiple categories" in {
      requireDb()
      wipeOurRows()
      seed(2, Some("politics"))
      seed(2, Some("economy"))
      seed(2, Some("sports"))
      val unioned = repo.findByCategoriesPool(List("politics", "economy"), limit = 50)
        .toOption.get
        .filter(_.author.contains(tag))
      unioned.length shouldBe 4
      unioned.flatMap(_.category).distinct.toSet shouldBe Set("politics", "economy")
    }

    "be case-insensitive on the input list" in {
      requireDb()
      wipeOurRows()
      seed(2, Some("politics"))
      val out = repo.findByCategoriesPool(List("POLITICS"), limit = 50)
        .toOption.get
        .filter(_.author.contains(tag))
      out.length shouldBe 2
    }
  }
}
