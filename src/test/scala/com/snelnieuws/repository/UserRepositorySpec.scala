package com.snelnieuws.repository

import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class UserRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val userRepo = new UserRepository(Database.transactor)
  private lazy val subRepo  = new NotificationSubscriptionRepository(Database.transactor)

  "UserRepository.upsert" should {
    "insert a new user" in {
      requireDb()
      val uid = "user-repo-spec-uid-1"
      userRepo.upsert(uid, "alice@example.com") shouldBe a[Right[_, _]]

      userRepo.findById(uid).toOption.flatten match {
        case Some(u) =>
          u.id    shouldBe uid
          u.email shouldBe Some("alice@example.com")
        case None => fail("user not inserted")
      }
    }

    "update email on conflict (idempotent)" in {
      requireDb()
      val uid = "user-repo-spec-uid-2"
      userRepo.upsert(uid, "old@example.com") shouldBe a[Right[_, _]]
      userRepo.upsert(uid, "new@example.com") shouldBe a[Right[_, _]]

      userRepo.findById(uid).toOption.flatten.map(_.email) shouldBe Some(Some("new@example.com"))
    }
  }

  "NotificationSubscriptionRepository.upsert with userId (self-heal)" should {
    "create a missing users row instead of failing the FK" in {
      requireDb()
      // No POST /users was ever made for this uid — the self-heal in upsert
      // is what guarantees subscribe doesn't FK-fail.
      val uid = "user-repo-spec-selfheal-uid"
      userRepo.findById(uid).toOption.flatten shouldBe None

      subRepo.upsert(
        "user-repo-spec-selfheal-device",
        "selfheal-token",
        2,
        userId = Some(uid)
      ) shouldBe a[Right[_, _]]

      // Subscription row is linked to the user.
      subRepo.lastFrequencyByUserId(uid) shouldBe Right(Some(2))
      // Users row was auto-created with no email.
      userRepo.findById(uid).toOption.flatten match {
        case Some(u) =>
          u.id    shouldBe uid
          u.email shouldBe None
        case None => fail("self-heal didn't create users row")
      }
    }

    "leave an existing users row's email untouched on self-heal" in {
      requireDb()
      val uid = "user-repo-spec-selfheal-existing"
      userRepo.upsert(uid, "existing@example.com") shouldBe a[Right[_, _]]

      // Self-heal path runs ON CONFLICT DO NOTHING — must not blank the email.
      subRepo.upsert(
        "user-repo-spec-selfheal-existing-device",
        "tok",
        2,
        userId = Some(uid)
      ) shouldBe a[Right[_, _]]

      userRepo.findById(uid).toOption.flatten.flatMap(_.email) shouldBe Some("existing@example.com")
    }
  }

  "UserRepository.deleteById" should {
    "remove the user and cascade-delete their subscription rows" in {
      requireDb()
      val uid = "user-repo-spec-uid-cascade"
      userRepo.upsert(uid, "cascade@example.com") shouldBe a[Right[_, _]]

      subRepo.upsert(
        "user-repo-spec-device-1",
        "cascade-token-1",
        2,
        userId = Some(uid)
      ) shouldBe a[Right[_, _]]
      // Sanity: the subscription row is linked to this user.
      subRepo.lastFrequencyByUserId(uid) shouldBe Right(Some(2))

      userRepo.deleteById(uid) match {
        case Right(rows) => rows shouldBe 1
        case Left(e)     => fail(e)
      }

      // User row gone.
      userRepo.findById(uid).toOption.flatten shouldBe None
      // Cascaded subscription row gone.
      subRepo.lastFrequencyByUserId(uid) shouldBe Right(None)
    }
  }

  "NotificationSubscriptionRepository.lastFrequencyByUserId" should {
    "return None when the user has no rows" in {
      requireDb()
      subRepo.lastFrequencyByUserId("user-with-no-rows") shouldBe Right(None)
    }

    "return the most-recently-updated row's frequency when there are multiple" in {
      requireDb()
      val uid = "user-repo-spec-uid-multi"
      userRepo.upsert(uid, "multi@example.com") shouldBe a[Right[_, _]]

      // Two devices on different frequencies, both linked to the same user.
      // The second one is upserted last so it's the most-recent update.
      subRepo.upsert("user-repo-spec-device-A", "tok-A", 1, Some(uid)) shouldBe a[Right[_, _]]
      subRepo.upsert("user-repo-spec-device-B", "tok-B", 4, Some(uid)) shouldBe a[Right[_, _]]

      subRepo.lastFrequencyByUserId(uid) shouldBe Right(Some(4))
    }
  }
}
