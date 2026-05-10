package com.snelnieuws.api

import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService, StubFcmMessagingService}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.typesafe.config.ConfigFactory
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class AndroidNotificationsServletV2Spec
    extends AnyWordSpec
    with ScalatraSuite
    with Matchers
    with DatabaseTestSupport {

  implicit lazy val jsonFormats: Formats = DefaultFormats

  private val testApiKey = "test-api-key"

  private val testConfig = ConfigFactory
    .parseString(
      s"""
         |notifications.enabled = true
         |notifications.api-key = "$testApiKey"
         |articles.cleanup.enabled = false
         |kafka.summarized-import.enabled = false
         |""".stripMargin
    )
    .withFallback(ConfigFactory.load())
    .resolve()

  private val stubApns = new StubApnsMessagingService(acceptAll = true)
  private val stubFcm  = new StubFcmMessagingService(acceptAll = true)

  private val testUidByToken = Map("alice-token" -> "uid-alice")
  private val stubVerifier   = new FirebaseTokenVerifier.Stub(testUidByToken)

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig        = testConfig,
    apns              = Some(stubApns),
    apnsSandbox       = None,
    fcm               = Some(stubFcm),
    verifierOverride  = Some(stubVerifier)
  )

  // Mounted at /v2/android/* — same prefix the bootstrap uses, so the
  // servlet sees the same requestPath ("/clients/register" etc.) it will
  // see in production.
  addServlet(components.androidNotificationsServletV2, "/v2/android/*")

  private lazy val gateClientId: String = {
    val id = UUID.randomUUID().toString
    val regBody = s"""{
      "clientId":  "$id",
      "bundleId":  "com.emudoi.snelnieuws",
      "osVersion": "Android 14"
    }"""
    val regHeaders = Map(
      "Content-Type" -> "application/json",
      "X-Client"     -> "android/1.4.0"
    )
    post("/v2/android/clients/register", regBody, regHeaders) {
      assert(status == 200, s"register precondition failed: HTTP $status, body=$body")
    }
    id
  }

  private def gatedHeaders: Map[String, String] = Map(
    "Content-Type" -> "application/json",
    "X-Client"     -> "android/1.4.0",
    "X-Client-Key" -> gateClientId
  )

  private def withAuth(token: String): Map[String, String] =
    gatedHeaders + ("Authorization" -> s"Bearer $token")

  "Gate" should {
    "return 403 when X-Client header is missing" in {
      requireDb()
      val body = """{"deviceId":"x","fcmToken":"y","frequency":1}"""
      post("/v2/android/notifications/subscribe", body, Map("Content-Type" -> "application/json")) {
        status shouldBe 403
      }
    }

    "return 403 when X-Client header is iOS" in {
      requireDb()
      val body = """{"deviceId":"x","fcmToken":"y","frequency":1}"""
      val headers = Map(
        "Content-Type" -> "application/json",
        "X-Client"     -> "ios/1.4.0"
      )
      post("/v2/android/notifications/subscribe", body, headers) {
        status shouldBe 403
      }
    }

    "return 401 when X-Client-Key is missing on a non-exempt route" in {
      requireDb()
      val body = """{"deviceId":"x","fcmToken":"y","frequency":1}"""
      val headers = Map(
        "Content-Type" -> "application/json",
        "X-Client"     -> "android/1.4.0"
      )
      post("/v2/android/notifications/subscribe", body, headers) {
        status shouldBe 401
      }
    }

    "exempt /clients/register from the X-Client-Key gate" in {
      requireDb()
      val id = UUID.randomUUID().toString
      val body = s"""{
        "clientId":  "$id",
        "bundleId":  "com.emudoi.snelnieuws",
        "osVersion": "Android 14"
      }"""
      val headers = Map(
        "Content-Type" -> "application/json",
        "X-Client"     -> "android/1.4.0"
      )
      post("/v2/android/clients/register", body, headers) {
        status shouldBe 200
      }
    }
  }

  "POST /v2/android/notifications/subscribe" should {
    "accept a valid anonymous subscription" in {
      requireDb()
      val body = s"""{
        "deviceId":  "android-spec-anon",
        "fcmToken":  "android-spec-token-anon",
        "frequency": 1
      }"""
      post("/v2/android/notifications/subscribe", body, gatedHeaders) {
        status shouldBe 200
        body should include("ok")
      }
    }

    "accept a subscription with a valid Bearer token" in {
      requireDb()
      val body = s"""{
        "deviceId":  "android-spec-alice",
        "fcmToken":  "android-spec-token-alice",
        "frequency": 2
      }"""
      post("/v2/android/notifications/subscribe", body, withAuth("alice-token")) {
        status shouldBe 200
      }
    }

    "reject when frequency is out of range" in {
      requireDb()
      val body = s"""{
        "deviceId":  "android-spec-bad-freq",
        "fcmToken":  "android-spec-token-bad-freq",
        "frequency": 7
      }"""
      post("/v2/android/notifications/subscribe", body, gatedHeaders) {
        status shouldBe 400
      }
    }

    "reject an invalid bearer token" in {
      requireDb()
      val body = s"""{
        "deviceId":  "android-spec-bad-token",
        "fcmToken":  "android-spec-token-bad-token",
        "frequency": 1
      }"""
      post("/v2/android/notifications/subscribe", body, withAuth("not-a-real-token")) {
        status shouldBe 401
      }
    }
  }

  "DELETE /v2/android/notifications/:deviceId" should {
    "remove an existing subscription" in {
      requireDb()
      val subBody = s"""{
        "deviceId":  "android-spec-del",
        "fcmToken":  "android-spec-token-del",
        "frequency": 1
      }"""
      post("/v2/android/notifications/subscribe", subBody, gatedHeaders) {
        status shouldBe 200
      }
      delete("/v2/android/notifications/android-spec-del", Map.empty[String, String], gatedHeaders) {
        status shouldBe 204
      }
    }

    "return 404 for an unknown deviceId" in {
      requireDb()
      delete("/v2/android/notifications/android-spec-unknown-device", Map.empty[String, String], gatedHeaders) {
        status shouldBe 404
      }
    }
  }
}
