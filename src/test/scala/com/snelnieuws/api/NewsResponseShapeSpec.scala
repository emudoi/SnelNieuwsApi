package com.snelnieuws.api

import com.snelnieuws.{Components, DatabaseTestSupport, StubApnsMessagingService}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.snelnieuws.model.ArticleCreate
import com.typesafe.config.ConfigFactory
import org.json4s.{DefaultFormats, Formats, JValue}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

/** Mobile contract guard. The iOS Codable struct (`Article` in
  *   emudoi-snelnieuws-ios/SnelNieuws/data/model/NewsFetch.swift)
  * and the Android `ArticleDto` (`ArticleDto.kt`) decode the v2 read
  * responses. Renaming or removing any of the fields below crashes those
  * decoders — even with the personalised-feed feature inactive (flag off)
  * or active (flag on).
  *
  * The required fields are inlined here as plain strings, not imported from
  * the mobile codebases. If the mobile contract genuinely changes, update
  * the mobile decoder first, then update the lists here in lockstep.
  */
class NewsResponseShapeSpec
    extends AnyWordSpec
    with ScalatraSuite
    with Matchers
    with DatabaseTestSupport {

  implicit lazy val jsonFormats: Formats = DefaultFormats

  private val testConfig = ConfigFactory
    .parseString(
      """
        |notifications.enabled = true
        |notifications.api-key = "shape-spec-key"
        |articles.cleanup.enabled = false
        |kafka.summarized-import.enabled = false
        |""".stripMargin
    )
    .withFallback(ConfigFactory.load())
    .resolve()

  private val stubApns = new StubApnsMessagingService(acceptAll = true)
  private val stubVerifier = new FirebaseTokenVerifier.Stub(Map.empty)

  private val components = new Components(
    provideTransactor = Database.transactor,
    rootConfig        = testConfig,
    apns              = Some(stubApns),
    apnsSandbox       = None,
    verifierOverride  = Some(stubVerifier)
  )

  addServlet(components.newsServletV2, "/v2/*")

  // The top-level envelope is always emitted by NewsFetchResponse so it's
  // strict-required.
  private val RequiredTopLevel = Seq("status", "totalResults", "articles")

  // The per-article fields below appear on an article whose source row
  // populated every field. iOS Codable (let X?) and Android
  // kotlinx.serialization (val X: String? = null) both use "decode if
  // present" semantics for Optional fields, so a missing key for a None
  // value at the source row is tolerated. We assert presence on an
  // article we seeded ourselves, where every field is non-null on the
  // wire — that's the contract the apps actually exercise when the
  // upstream summarizer populates a full record.
  private val RequiredPerArticle = Seq(
    "id", "author", "title", "description",
    "url", "urlToImage", "publishedAt", "content"
  )

  private def freshClient(): String = {
    val id = UUID.randomUUID().toString
    val regBody = s"""{
      "clientId":  "$id",
      "bundleId":  "com.emudoi.snelnieuws",
      "osVersion": "iOS 18.0"
    }"""
    post(
      "/v2/clients/register",
      regBody,
      Map("Content-Type" -> "application/json", "X-Client" -> "ios/1.4.0")
    ) {
      assert(status == 200, s"register failed: HTTP $status, body=$body")
    }
    id
  }

  private def hasField(j: JValue, name: String): Boolean =
    j.findField { case (n, _) => n == name }.isDefined

  private def assertShape(jsonStr: String, expectedTitle: String): Unit = {
    val parsed = org.json4s.jackson.parseJson(jsonStr)
    RequiredTopLevel.foreach { f =>
      withClue(s"missing top-level field '$f' in: ${jsonStr.take(200)}") {
        hasField(parsed, f) shouldBe true
      }
    }
    val articles = (parsed \ "articles").children
    articles should not be empty
    // Find the article we just seeded — its title is unique so we can
    // pick it out of the response without depending on ordering.
    val ours = articles.find(a => (a \ "title").extract[String] == expectedTitle)
    withClue(
      s"could not find seeded article '$expectedTitle' in response. " +
      s"Returned titles: ${articles.map(a => (a \ "title").extract[String]).take(5)}"
    ) {
      ours shouldBe defined
    }
    RequiredPerArticle.foreach { f =>
      withClue(s"missing article field '$f' on the seeded article") {
        hasField(ours.get, f) shouldBe true
      }
    }
  }

  private def seedOne(): String = {
    val title = s"shape-spec-${UUID.randomUUID()}"
    components.articleService.create(ArticleCreate(
      author      = Some("shape-spec"),
      title       = title,
      description = Some("desc"),
      url         = s"https://example.com/shape/${UUID.randomUUID()}",
      urlToImage  = Some("https://example.com/img.jpg"),
      content     = Some("content"),
      category    = Some("technology")
    )) shouldBe a[Right[_, _]]
    title
  }

  "GET /v2/everything" should {
    "preserve the mobile JSON shape with the flag off" in {
      requireDb()
      components.featureFlagRepository.setEnabled("personalised_feed_enabled", enabled = false)
      val title = seedOne()
      val cid = freshClient()
      // Bigger pageSize to make sure our seeded article is included even
      // if other suites' fresher rows pushed it past the top 100.
      get("/v2/everything", Map("pageSize" -> "300"), Map(
        "X-Client" -> "ios/1.4.0", "X-Client-Key" -> cid
      )) {
        status shouldBe 200
        assertShape(body, title)
      }
    }

    "preserve the mobile JSON shape with the flag on" in {
      requireDb()
      components.featureFlagRepository.setEnabled("personalised_feed_enabled", enabled = true)
      val title = seedOne()
      val cid = freshClient()
      get("/v2/everything", Map("pageSize" -> "300"), Map(
        "X-Client" -> "ios/1.4.0", "X-Client-Key" -> cid
      )) {
        status shouldBe 200
        assertShape(body, title)
      }
      components.featureFlagRepository.setEnabled("personalised_feed_enabled", enabled = false)
    }
  }
}
