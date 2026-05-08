package com.snelnieuws.model

case class NotificationSubscription(
  deviceId: String,
  apnsToken: String,
  frequency: Int,
  createdAt: String,
  updatedAt: String
)

case class SubscribeRequest(
  deviceId: String,
  apnsToken: String,
  frequency: Int,
  // "production" | "sandbox". Defaults to "production" so iOS builds that
  // predate the field keep working — old binaries can only have shipped
  // production tokens (no sandbox build path existed before this change).
  environment: String = "production"
)

case class DispatchResponse(
  sent: Int,
  failed: Int,
  newArticles: Int
)

case class User(
  id: String,
  email: Option[String],
  createdAt: String,
  updatedAt: String
)

case class UpsertUserRequest(email: String)

case class LastPreferenceResponse(frequency: Int)

case class RegisterClientRequest(
  clientId: String,
  bundleId: String,
  osVersion: Option[String]
)

case class CategoriesPayload(categories: List[String])
