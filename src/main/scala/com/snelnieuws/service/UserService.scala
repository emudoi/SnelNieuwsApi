package com.snelnieuws.service

import com.snelnieuws.repository.{
  AndroidNotificationSubscriptionRepository,
  NotificationSubscriptionRepository,
  UserRepository
}
import org.slf4j.LoggerFactory

class UserService(
  userRepository: UserRepository,
  subscriptionRepository: NotificationSubscriptionRepository,
  androidSubscriptionRepository: AndroidNotificationSubscriptionRepository
) {
  private val logger = LoggerFactory.getLogger(classOf[UserService])

  def upsert(uid: String, email: String): Either[Throwable, Int] =
    userRepository.upsert(uid, email)

  def lastFrequency(uid: String): Either[Throwable, Option[Int]] =
    subscriptionRepository.lastFrequencyByUserId(uid)

  /** Same as `lastFrequency` but reads from the Android subscription
    * table. Used by GET /v2/users/me/last-preference when the caller's
    * X-Client header is `android/<v>`, so the platforms keep their per-
    * device frequency separation.
    */
  def lastFrequencyAndroid(uid: String): Either[Throwable, Option[Int]] =
    androidSubscriptionRepository.lastFrequencyByUserId(uid)

  def findCategories(uid: String): Either[Throwable, Option[List[String]]] =
    userRepository.findCategories(uid)

  def saveCategories(uid: String, categories: List[String]): Either[Throwable, Int] =
    userRepository.saveCategories(uid, categories)

  /** Delete the user. Cleans Android subscription rows first (no FK
    * cascade exists for that table, so the rows would orphan otherwise),
    * then deletes the users row — which cascade-deletes the iOS
    * subscriptions via the existing FK.
    *
    * If the Android cleanup fails we surface that error and DO NOT delete
    * the user. That keeps the operation atomic-ish: either both sides
    * succeed or the caller can retry.
    */
  def delete(uid: String): Either[Throwable, Int] =
    androidSubscriptionRepository.deleteByUserId(uid) match {
      case Left(e) =>
        logger.error(s"Refusing to delete user=$uid because Android cleanup failed: ${e.getMessage}", e)
        Left(e)
      case Right(_) =>
        userRepository.deleteById(uid)
    }
}
