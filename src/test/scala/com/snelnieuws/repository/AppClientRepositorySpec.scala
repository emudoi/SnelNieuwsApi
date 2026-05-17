package com.snelnieuws.repository

import cats.effect.unsafe.implicits.global
import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import doobie.implicits._
import doobie.postgres.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Exercises the personalised-feed JSONB column wiring on app_clients.
  *
  * The concurrency test seeds disjoint id ranges from two parallel futures
  * and asserts no lost updates — this validates the SELECT ... FOR UPDATE
  * inside appendServedIds. If this test ever goes flaky, the fix is in
  * AppClientRepository (the transaction), not in test seeds or timing.
  */
class AppClientRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val repo = new AppClientRepository(Database.transactor)

  // Each test registers a fresh client so suite ordering doesn't matter.
  private def registerClient(): UUID = {
    val id = UUID.randomUUID()
    repo.upsertOnRegister(
      clientId  = id,
      bundleId  = "com.emudoi.snelnieuws",
      osVersion = Some("test"),
      platform  = "ios"
    ) shouldBe a[Right[_, _]]
    id
  }

  private def readArrayLength(clientId: UUID): Int =
    sql"SELECT jsonb_array_length(last_served_ids) FROM app_clients WHERE client_id = $clientId"
      .query[Int].unique.transact(Database.transactor).unsafeRunSync()

  "AppClientRepository.readServedIds" should {
    "return empty for a newly-registered client" in {
      requireDb()
      val cid = registerClient()
      repo.readServedIds(cid) shouldBe Right(Set.empty[Long])
    }

    "return empty for an unknown client_id (tolerant read)" in {
      requireDb()
      // A UUID that was never registered → no row → method must not error.
      repo.readServedIds(UUID.randomUUID()) shouldBe Right(Set.empty[Long])
    }
  }

  "AppClientRepository.appendServedIds" should {
    "no-op cleanly on an empty list" in {
      requireDb()
      val cid = registerClient()
      repo.appendServedIds(cid, Nil) shouldBe Right(0)
      readArrayLength(cid) shouldBe 0
    }

    "append, dedupe, and persist ids" in {
      requireDb()
      val cid = registerClient()
      repo.appendServedIds(cid, List(10L, 20L, 30L)) shouldBe a[Right[_, _]]
      // Second call adds new id + repeats one already present.
      repo.appendServedIds(cid, List(20L, 40L)) shouldBe a[Right[_, _]]
      repo.readServedIds(cid) shouldBe Right(Set(10L, 20L, 30L, 40L))
    }

    "trim to capAt keeping the most-recently-appended entries" in {
      requireDb()
      val cid = registerClient()
      // Append 7 ids with cap=5 → expect the last 5 to win.
      repo.appendServedIds(cid, List(1L, 2L, 3L, 4L, 5L, 6L, 7L), capAt = 5) shouldBe a[Right[_, _]]
      readArrayLength(cid) shouldBe 5
      repo.readServedIds(cid) shouldBe Right(Set(3L, 4L, 5L, 6L, 7L))
    }
  }

  "AppClientRepository.setServedIds" should {
    "replace the column wholesale" in {
      requireDb()
      val cid = registerClient()
      repo.appendServedIds(cid, List(1L, 2L, 3L)) shouldBe a[Right[_, _]]
      repo.setServedIds(cid, List(99L, 100L)) shouldBe a[Right[_, _]]
      repo.readServedIds(cid) shouldBe Right(Set(99L, 100L))
    }

    "accept an empty list and clear the column" in {
      requireDb()
      val cid = registerClient()
      repo.appendServedIds(cid, List(1L, 2L, 3L)) shouldBe a[Right[_, _]]
      repo.setServedIds(cid, Nil) shouldBe a[Right[_, _]]
      readArrayLength(cid) shouldBe 0
    }
  }

  "AppClientRepository (concurrency)" should {
    "not lose updates when two appends race on the same client_id" in {
      requireDb()
      val cid = registerClient()
      implicit val ec: ExecutionContext = ExecutionContext.global

      // Two disjoint id ranges, racing. The FOR UPDATE inside appendServedIds
      // serialises the read-merge-write, so the final array must contain
      // every id from both ranges.
      val rangeA = (1L to 50L).toList
      val rangeB = (51L to 100L).toList
      val f = Future.sequence(List(
        Future(repo.appendServedIds(cid, rangeA)),
        Future(repo.appendServedIds(cid, rangeB))
      ))
      val results = Await.result(f, 15.seconds)
      results.foreach(_ shouldBe a[Right[_, _]])

      repo.readServedIds(cid) shouldBe Right((1L to 100L).toSet)
    }
  }
}
