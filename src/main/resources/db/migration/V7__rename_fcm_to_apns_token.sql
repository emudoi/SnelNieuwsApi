-- Switched from FCM to direct APNs. The column now stores the APNs device
-- token (64-char hex) directly, not an FCM token. iOS-side capture moves
-- from MessagingDelegate to didRegisterForRemoteNotificationsWithDeviceToken.

ALTER TABLE notification_subscriptions
    RENAME COLUMN fcm_token TO apns_token;
