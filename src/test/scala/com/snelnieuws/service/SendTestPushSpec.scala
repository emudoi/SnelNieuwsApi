package com.snelnieuws.service

import cats.effect.IO
import com.snelnieuws.repository.NotificationSubscriptionRepository
import doobie.hikari.HikariTransactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * One-shot manual integration test that sends a real APNs push to a hardcoded
 * device token. Skipped (via `assume`) unless every env var below is set, so it
 * never fires in CI. Talks to Apple directly — no Vault, no DB, no deployment.
 *
 * Required env vars:
 *   APNS_KEY_PATH       absolute path to the .p8 file
 *   APNS_KEY_ID         10-char Key ID (e.g. U5Z9BW99GS)
 *   APNS_TEAM_ID        10-char Team ID (e.g. 7PB86SYNNM)
 *   APNS_BUNDLE_ID      e.g. com.emudoi.snelnieuws
 *   APNS_SANDBOX        true for Xcode-debug-build device tokens, false for
 *                       distribution-signed builds (TestFlight / Ad Hoc / App Store)
 *   APNS_DEVICE_TOKEN   64-char hex APNs token from the device
 *
 * Run from the project root:
 *   APNS_KEY_PATH=/Users/.../AuthKey_U5Z9BW99GS.p8 \
 *   APNS_KEY_ID=U5Z9BW99GS \
 *   APNS_TEAM_ID=7PB86SYNNM \
 *   APNS_BUNDLE_ID=com.emudoi.snelnieuws \
 *   APNS_SANDBOX=true \
 *   APNS_DEVICE_TOKEN=<paste-from-Xcode-console> \
 *   sbt 'testOnly com.snelnieuws.service.SendTestPushSpec'
 *
 * Expected outcome on success: a notification arrives on the iPhone within a
 * couple of seconds, and the test prints `sent=1, failed=0`. On failure, the
 * library logs the exact APNs rejection reason at WARN level, e.g.
 * `BadDeviceToken` (sandbox/production mismatch), `InvalidProviderToken`
 * (bad team/key/.p8 combo), or `TopicDisallowed` (bundle not on this team).
 */
class SendTestPushSpec extends AnyWordSpec with Matchers {

  "PushyApnsMessagingService" should {
    // Disabled — manual one-off integration test. To run it again later,
    // change `ignore` back to `in` and execute via ./test-send-push.sh.
    "deliver a push to a hardcoded APNs device token" ignore {
      val keyPath     = sys.env.getOrElse("APNS_KEY_PATH", "")
      val keyId       = sys.env.getOrElse("APNS_KEY_ID", "")
      val teamId      = sys.env.getOrElse("APNS_TEAM_ID", "")
      val bundleId    = sys.env.getOrElse("APNS_BUNDLE_ID", "")
      val sandboxStr  = sys.env.getOrElse("APNS_SANDBOX", "")
      val deviceToken = sys.env.getOrElse("APNS_DEVICE_TOKEN", "")

      assume(keyPath.nonEmpty,     "APNS_KEY_PATH not set — skipping")
      assume(keyId.nonEmpty,       "APNS_KEY_ID not set — skipping")
      assume(teamId.nonEmpty,      "APNS_TEAM_ID not set — skipping")
      assume(bundleId.nonEmpty,    "APNS_BUNDLE_ID not set — skipping")
      assume(sandboxStr.nonEmpty,  "APNS_SANDBOX not set — skipping")
      assume(deviceToken.nonEmpty, "APNS_DEVICE_TOKEN not set — skipping")

      val sandbox = sandboxStr.toBoolean

      println(s"[SendTestPushSpec] keyId=$keyId teamId=$teamId bundle=$bundleId " +
        s"sandbox=$sandbox tokenPrefix=${deviceToken.take(8)} tokenLen=${deviceToken.length}")

      val cfg     = ApnsConfig(keyPath, keyId, teamId, bundleId, sandbox)
      val service = new PushyApnsMessagingService(NoOpSubscriptionRepository, cfg)

      val (sent, failed) = service.sendBatch(
        tokens = List(deviceToken),
        title  = "Test push",
        body   = "Hello from snelnieuws-api"
      )

      println(s"[SendTestPushSpec] result: sent=$sent failed=$failed")

      // Hard-fail when APNs rejects so the runner exit code is non-zero. The
      // library will have already logged the reason at WARN level above.
      sent shouldBe 1
      failed shouldBe 0
    }
  }
}

/** Subclass that overrides only the one method `PushyApnsMessagingService` may
 *  call (when APNs returns Unregistered/BadDeviceToken). The base class' lazy
 *  `transactor` is never resolved, so passing `null` is safe.
 */
private object NoOpSubscriptionRepository
    extends NotificationSubscriptionRepository(null.asInstanceOf[HikariTransactor[IO]]) {
  override def deleteByApnsToken(token: String): Either[Throwable, Int] = {
    println(s"[SendTestPushSpec] would-prune dead token=${token.take(8)}...")
    Right(0)
  }
}
