package com.snelnieuws.service

import com.snelnieuws.repository.{NotificationSubscriptionRepository, UserRepository}

class UserService(
  userRepository: UserRepository,
  subscriptionRepository: NotificationSubscriptionRepository
) {
  def upsert(uid: String, email: String): Either[Throwable, Int] =
    userRepository.upsert(uid, email)

  def lastFrequency(uid: String): Either[Throwable, Option[Int]] =
    subscriptionRepository.lastFrequencyByUserId(uid)

  def findCategories(uid: String): Either[Throwable, Option[List[String]]] =
    userRepository.findCategories(uid)

  def saveCategories(uid: String, categories: List[String]): Either[Throwable, Int] =
    userRepository.saveCategories(uid, categories)

  def delete(uid: String): Either[Throwable, Int] =
    userRepository.deleteById(uid)
}
