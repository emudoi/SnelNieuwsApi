package com.snelnieuws.model

case class NotificationSubscription(
  deviceId: String,
  fcmToken: String,
  frequency: Int,
  createdAt: String,
  updatedAt: String
)

case class SubscribeRequest(
  deviceId: String,
  fcmToken: String,
  frequency: Int
)

case class DispatchResponse(
  sent: Int,
  failed: Int,
  newArticles: Int
)
