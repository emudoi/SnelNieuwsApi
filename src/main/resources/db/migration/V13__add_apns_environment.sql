-- Tag each subscription with the APNs environment its token came from.
-- Sandbox tokens (Xcode-debug builds, aps-environment=development) only
-- work against api.sandbox.push.apple.com; production tokens (TestFlight
-- + App Store builds) only work against api.push.apple.com. Storing the
-- environment lets the prod and sandbox dispatch endpoints filter to
-- their own subscribers and avoids cross-environment BadDeviceToken
-- pruning storms when both endpoints share one .p8.
--
-- Existing rows are production by definition — there was no other path
-- until now. New iOS builds send `environment` on subscribe; older builds
-- that don't send the field default to 'production' at the model layer.
ALTER TABLE notification_subscriptions
    ADD COLUMN IF NOT EXISTS apns_environment TEXT NOT NULL DEFAULT 'production'
        CHECK (apns_environment IN ('production', 'sandbox'));

CREATE INDEX IF NOT EXISTS idx_notif_sub_env_freq
    ON notification_subscriptions(apns_environment, frequency);
