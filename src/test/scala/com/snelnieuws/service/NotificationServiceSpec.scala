package com.snelnieuws.service

import com.snelnieuws.{DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.db.Database
import com.snelnieuws.model.{ArticleCreate, SubscribeRequest}
import com.snelnieuws.repository.{
  ArticleRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NotificationServiceSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val articleRepo      = new ArticleRepository(Database.transactor)
  private lazy val subRepo          = new NotificationSubscriptionRepository(Database.transactor)
  private lazy val dispatchRepo     = new NotificationDispatchRepository(Database.transactor)

  private def newService(apnsProd: Option[ApnsMessagingService] = None,
                         apnsSandbox: Option[ApnsMessagingService] = None) =
    new NotificationService(articleRepo, subRepo, dispatchRepo,
      apnsProd = apnsProd, apnsSandbox = apnsSandbox)

  "subscribe" should {
    "upsert a subscription row" in {
      requireDb()
      val service = newService()
      val req = SubscribeRequest(
        deviceId  = "ns-spec-device-1",
        apnsToken = "ns-spec-token-1",
        frequency = 1
      )
      service.subscribe(req) shouldBe a[Right[_, _]]

      // Idempotent — a second call with a different token updates rather than failing.
      val req2 = req.copy(apnsToken = "ns-spec-token-1-new")
      service.subscribe(req2) shouldBe a[Right[_, _]]

      val tokens = subRepo.findTokensByFrequency(1).toOption.getOrElse(Nil)
      tokens should contain("ns-spec-token-1-new")
      tokens should not contain "ns-spec-token-1"
    }
  }

  "dispatch" should {
    "return Disabled when the matching apns client is None" in {
      requireDb()
      val service = newService()
      service.dispatch(frequency = None, environment = "production") match {
        case Right(DispatchOutcome.Disabled) => succeed
        case other                           => fail(s"Expected Disabled, got: $other")
      }
      service.dispatch(frequency = None, environment = "sandbox") match {
        case Right(DispatchOutcome.Disabled) => succeed
        case other                           => fail(s"Expected Disabled, got: $other")
      }
    }

    "return Sent with sent=0 when there are no tokens for the frequency" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      // Use a frequency tier we know has no subscribers.
      service.dispatch(frequency = Some(3), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent shouldBe 0
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }
    }

    "send to subscribers for the given frequency when there are new articles" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      // Subscribe a unique device on frequency=2 (default environment=production).
      service.subscribe(
        SubscribeRequest(
          deviceId  = "ns-spec-device-2",
          apnsToken = "ns-spec-token-2",
          frequency = 2
        )
      ) shouldBe a[Right[_, _]]

      // Insert a fresh article so countSinceId > 0.
      articleRepo.create(
        ArticleCreate(
          author      = Some("ns-spec"),
          title       = "NotificationServiceSpec dispatch trigger",
          description = None,
          url         = "https://example.com/ns-spec/dispatch",
          urlToImage  = None,
          content     = None,
          category    = Some("ns-spec")
        )
      ) shouldBe a[Right[_, _]]

      service.dispatch(frequency = Some(2), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.sent should be >= 1
          resp.failed shouldBe 0
          resp.newArticles should be >= 1
        case other =>
          fail(s"Expected Sent, got: $other")
      }

      stub.batches should not be empty
      stub.batches.flatMap(_.tokens) should contain("ns-spec-token-2")
    }

    "return Sent with newArticles=0 immediately after a previous dispatch with no inserts" in {
      requireDb()
      val stub    = new StubApnsMessagingService(acceptAll = true)
      val service = newService(apnsProd = Some(stub))

      // Frequency=2 was just dispatched in the previous test — no fresh inserts here.
      service.dispatch(frequency = Some(2), environment = "production") match {
        case Right(DispatchOutcome.Sent(resp)) =>
          resp.newArticles shouldBe 0
          resp.sent shouldBe 0
          resp.failed shouldBe 0
        case other =>
          fail(s"Expected Sent, got: $other")
      }
      stub.batches shouldBe empty
    }

    "route sandbox dispatches to the sandbox client and skip production tokens" in {
      requireDb()
      val prodStub    = new StubApnsMessagingService(acceptAll = true)
      val sandboxStub = new StubApnsMessagingService(acceptAll = true)
      val service     = newService(apnsProd = Some(prodStub), apnsSandbox = Some(sandboxStub))

      // One sandbox-tagged subscriber + one production-tagged subscriber on freq=4.
      service.subscribe(
        SubscribeRequest(
          deviceId    = "ns-spec-device-sandbox",
          apnsToken   = "ns-spec-token-sandbox",
          frequency   = 4,
          environment = "sandbox"
        )
      ) shouldBe a[Right[_, _]]
      service.subscribe(
        SubscribeRequest(
          deviceId    = "ns-spec-device-prod",
          apnsToken   = "ns-spec-token-prod",
          frequency   = 4,
          environment = "production"
        )
      ) shouldBe a[Right[_, _]]

      // Fresh article so we actually attempt sends.
      articleRepo.create(
        ArticleCreate(
          author      = Some("ns-spec"),
          title       = "NotificationServiceSpec sandbox routing",
          description = None,
          url         = "https://example.com/ns-spec/sandbox",
          urlToImage  = None,
          content     = None,
          category    = Some("ns-spec")
        )
      ) shouldBe a[Right[_, _]]

      service.dispatch(frequency = Some(4), environment = "sandbox") match {
        case Right(DispatchOutcome.Sent(_)) => succeed
        case other                          => fail(s"Expected Sent, got: $other")
      }

      sandboxStub.batches.flatMap(_.tokens) should contain("ns-spec-token-sandbox")
      sandboxStub.batches.flatMap(_.tokens) should not contain "ns-spec-token-prod"
      prodStub.batches shouldBe empty
    }
  }
}
