package com.snelnieuws

import cats.effect.IO
import com.snelnieuws.api.{
  HealthServlet,
  ImageServlet,
  NewsServlet,
  NewsServletV2,
  NotificationDispatchServlet,
  StaticContentServlet
}
import com.snelnieuws.auth.FirebaseTokenVerifier
import com.snelnieuws.db.Database
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.repository.{
  AppClientRepository,
  ArticleRepository,
  ImageCacheRepository,
  NotificationDispatchRepository,
  NotificationSubscriptionRepository,
  UserRepository
}
import com.snelnieuws.service.{
  ApnsConfig,
  ApnsMessagingService,
  ArticleCleanupScheduler,
  ArticleService,
  ImageCacheCleanupScheduler,
  ImageCacheConfig,
  ImageCacheService,
  ImageDownloadWorker,
  NotificationService,
  PushyApnsMessagingService,
  SummarizedArticleConsumer,
  UserService
}
import com.typesafe.config.{Config, ConfigFactory}
import doobie.hikari.HikariTransactor
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}

class Components(
  provideTransactor: => HikariTransactor[IO],
  rootConfig: Config,
  apns: Option[ApnsMessagingService],
  verifierOverride: Option[FirebaseTokenVerifier] = None
) {

  private val logger = LoggerFactory.getLogger(classOf[Components])

  // Repositories
  lazy val articleRepository: ArticleRepository =
    new ArticleRepository(provideTransactor)
  lazy val notificationSubscriptionRepository: NotificationSubscriptionRepository =
    new NotificationSubscriptionRepository(provideTransactor)
  lazy val notificationDispatchRepository: NotificationDispatchRepository =
    new NotificationDispatchRepository(provideTransactor)
  lazy val userRepository: UserRepository =
    new UserRepository(provideTransactor)
  lazy val appClientRepository: AppClientRepository =
    new AppClientRepository(provideTransactor)
  lazy val imageCacheRepository: ImageCacheRepository =
    new ImageCacheRepository(provideTransactor)

  // Image cache config — single source of truth read once on construct.
  private val imagesCfg = rootConfig.getConfig("images")
  val imagesPublicBaseUrl: String = imagesCfg.getString("public-base-url")
  private val imageCacheServiceConfig: ImageCacheConfig = ImageCacheConfig(
    rootDir             = imagesCfg.getString("root-dir"),
    downloadTimeoutMs   = imagesCfg.getLong("download-timeout-ms"),
    maxBytes            = imagesCfg.getLong("max-bytes"),
    userAgent           = imagesCfg.getString("user-agent"),
    maxAttempts         = imagesCfg.getInt("max-attempts"),
    retryBackoffMinutes = imagesCfg.getLong("retry-backoff-minutes")
  )

  // Notification config (api-key needed by servlet for transport-level auth)
  private val notificationsConfig         = rootConfig.getConfig("notifications")
  val notificationsApiKey: String         = notificationsConfig.getString("api-key")

  // Firebase ID token verifier. Tests pass a Stub via verifierOverride.
  // Production reads firebase.project-id from config; if empty we fall
  // back to RejectAll so the auth-required endpoints stay locked rather
  // than letting unverified requests through.
  lazy val firebaseVerifier: FirebaseTokenVerifier = {
    verifierOverride.getOrElse {
      val firebaseCfg = rootConfig.getConfig("firebase")
      val projectId   = firebaseCfg.getString("project-id")
      if (projectId.isEmpty) {
        logger.warn(
          "firebase.project-id is empty — auth-required endpoints will reject all requests. " +
            "Set FIREBASE_PROJECT_ID to enable Firebase ID token verification."
        )
        FirebaseTokenVerifier.RejectAll
      } else {
        try {
          new FirebaseTokenVerifier.FirebaseAdmin(
            projectId          = projectId,
            serviceAccountPath = firebaseCfg.getString("service-account-path")
          )
        } catch {
          case e: Exception =>
            logger.error(
              s"Failed to initialize Firebase Admin SDK: ${e.getMessage}. " +
                "Falling back to RejectAll.",
              e
            )
            FirebaseTokenVerifier.RejectAll
        }
      }
    }
  }

  // Services
  lazy val imageCacheService: ImageCacheService =
    new ImageCacheService(
      repository = imageCacheRepository,
      httpClient = ImageCacheService.defaultHttpClient(imageCacheServiceConfig),
      config     = imageCacheServiceConfig
    )

  lazy val imageDownloadWorker: ImageDownloadWorker =
    new ImageDownloadWorker(
      imageCacheService = imageCacheService,
      workerThreads     = imagesCfg.getInt("worker-threads"),
      queueCapacity     = imagesCfg.getInt("queue-capacity")
    )

  lazy val imageCacheCleanupScheduler: Option[ImageCacheCleanupScheduler] = {
    val cleanupCfg = imagesCfg.getConfig("cleanup")
    if (cleanupCfg.getBoolean("enabled")) {
      Some(
        new ImageCacheCleanupScheduler(
          imageCacheRepository = imageCacheRepository,
          rootDir              = imagesCfg.getString("root-dir"),
          retentionHours       = cleanupCfg.getLong("retention-hours"),
          intervalMinutes      = cleanupCfg.getLong("interval-minutes")
        )
      )
    } else {
      logger.info("Image cache cleanup scheduler is disabled (images.cleanup.enabled=false)")
      None
    }
  }

  lazy val articleService: ArticleService =
    new ArticleService(
      repository          = articleRepository,
      imageCacheService   = imageCacheService,
      imageDownloadWorker = imageDownloadWorker,
      publicBaseUrl       = imagesPublicBaseUrl
    )

  lazy val notificationService: NotificationService =
    new NotificationService(
      articleRepository,
      notificationSubscriptionRepository,
      notificationDispatchRepository,
      apns
    )

  lazy val userService: UserService =
    new UserService(userRepository, notificationSubscriptionRepository)

  // Schedulers / consumers
  private val cleanupCfg = rootConfig.getConfig("articles.cleanup")
  lazy val articleCleanupScheduler: Option[ArticleCleanupScheduler] =
    if (cleanupCfg.getBoolean("enabled")) {
      Some(
        new ArticleCleanupScheduler(
          articleRepository = articleRepository,
          retentionHours    = cleanupCfg.getLong("retention-hours"),
          intervalMinutes   = cleanupCfg.getLong("interval-minutes")
        )
      )
    } else {
      logger.info("Article cleanup scheduler is disabled (articles.cleanup.enabled=false)")
      None
    }

  lazy val summarizedArticleConsumer: Option[SummarizedArticleConsumer] = {
    val kafkaCfg = SummarizedImportKafkaConfig.load(rootConfig)
    if (kafkaCfg.enabled) {
      try Some(
        new SummarizedArticleConsumer(
          articleRepository   = articleRepository,
          kafkaConfig         = kafkaCfg,
          imageCacheService   = imageCacheService,
          imageDownloadWorker = imageDownloadWorker
        )
      )
      catch {
        case e: Exception =>
          // Don't crash the API if Kafka is down — just log it.
          logger.error(s"Failed to construct summarized-article consumer: ${e.getMessage}", e)
          None
      }
    } else {
      logger.info("Summarized-article Kafka consumer is disabled (kafka.summarized-import.enabled=false)")
      None
    }
  }

  // Servlets
  lazy val newsServlet: NewsServlet =
    new NewsServlet(
      articleService,
      notificationService,
      userService,
      firebaseVerifier
    )
  lazy val newsServletV2: NewsServletV2 =
    new NewsServletV2(
      articleService,
      notificationService,
      userService,
      appClientRepository,
      firebaseVerifier
    )
  lazy val notificationDispatchServlet: NotificationDispatchServlet =
    new NotificationDispatchServlet(notificationService, notificationsApiKey)
  lazy val staticContentServlet: StaticContentServlet =
    new StaticContentServlet
  lazy val healthServlet: HealthServlet =
    new HealthServlet
  lazy val imageServlet: ImageServlet =
    new ImageServlet(imageCacheService)

  /** Eagerly resolve background workers and start them. Idempotent. */
  def startBackgroundWorkers(): Unit = {
    articleCleanupScheduler.foreach(_.start())
    imageDownloadWorker.start()
    imageCacheCleanupScheduler.foreach(_.start())
    summarizedArticleConsumer.foreach(_.start())
  }

  def close(): Unit = {
    logger.info("Shutting down components...")
    // Order matters: stop the producer of work first, then drain the
    // worker before letting the JVM exit so in-flight downloads either
    // finish or are abandoned cleanly. Cleanup schedulers are
    // independent and can stop in any order.
    summarizedArticleConsumer.foreach(_.stop())
    imageDownloadWorker.stop()
    imageCacheCleanupScheduler.foreach(_.stop())
    articleCleanupScheduler.foreach(_.stop())
  }
}

object Components {

  private val logger = LoggerFactory.getLogger(classOf[Components])

  def default(): Components = {
    val cfg  = ConfigFactory.load()
    val apns = buildApnsFromConfig(cfg)
    new Components(
      provideTransactor = Database.transactor,
      rootConfig = cfg,
      apns = apns
    )
  }

  // APNs is only built when notifications are enabled AND config is valid AND
  // the .p8 key file exists. Init failures are fail-soft: dispatch will report
  // "disabled" until the next deploy fixes the config.
  private def buildApnsFromConfig(cfg: Config): Option[ApnsMessagingService] = {
    val notifCfg = cfg.getConfig("notifications")
    if (!notifCfg.getBoolean("enabled")) {
      logger.info("Notifications are disabled (notifications.enabled=false)")
      None
    } else {
      val apnsCfg = notifCfg.getConfig("apns")
      val ac = ApnsConfig(
        keyPath  = apnsCfg.getString("key-path"),
        keyId    = apnsCfg.getString("key-id"),
        teamId   = apnsCfg.getString("team-id"),
        bundleId = apnsCfg.getString("bundle-id"),
        sandbox  = apnsCfg.getBoolean("sandbox")
      )
      if (ac.keyId.isEmpty || ac.teamId.isEmpty) {
        logger.warn("Notifications enabled but APNs key-id or team-id missing — dispatch will be a no-op")
        None
      } else if (!Files.exists(Paths.get(ac.keyPath))) {
        logger.warn(s"Notifications enabled but APNs key file not found at ${ac.keyPath} — dispatch will be a no-op")
        None
      } else {
        try {
          val subRepo = new NotificationSubscriptionRepository(Database.transactor)
          Some(new PushyApnsMessagingService(subRepo, ac))
        } catch {
          case e: Exception =>
            logger.error(s"Failed to initialize APNs client: ${e.getMessage}", e)
            None
        }
      }
    }
  }
}
