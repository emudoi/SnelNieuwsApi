-- Android notification stack — fully parallel to the iOS APNs stack.
-- These tables exist to keep iOS notification flow untouched while we add
-- FCM-based Android pushes. No FK to notification_subscriptions / dispatches;
-- the only shared infrastructure is `articles` (read-only watermark source)
-- and `app_clients` (install attestation, already platform-aware via its
-- `platform` column).
--
-- FCM has no sandbox/production split (debug + release builds hit the same
-- fcm.googleapis.com endpoint and Firebase routes by API key), so unlike
-- notification_subscriptions we don't carry an environment column.

CREATE TABLE IF NOT EXISTS android_notification_subscriptions (
    device_id   VARCHAR(64) PRIMARY KEY,
    fcm_token   TEXT        NOT NULL,
    frequency   INT         NOT NULL CHECK (frequency BETWEEN 1 AND 4),
    -- Firebase UID. No FK on purpose — keeps this table independent of the
    -- iOS-driven `users` table. iOS-side already self-heals missing user
    -- rows on subscribe; Android can do the same when/if it needs to link.
    user_id     VARCHAR(128) NULL,
    -- Install identity from X-Client-Key. No FK so a revoke-then-resubscribe
    -- cycle doesn't have to ripple here; the app_clients gate is enforced
    -- at the servlet layer on every request anyway.
    client_id   UUID         NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_android_notif_sub_frequency
    ON android_notification_subscriptions(frequency);
CREATE INDEX IF NOT EXISTS idx_android_notif_sub_user_id
    ON android_notification_subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_android_notif_sub_client_id
    ON android_notification_subscriptions(client_id);


CREATE TABLE IF NOT EXISTS android_notification_dispatches (
    id                BIGSERIAL  PRIMARY KEY,
    dispatched_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- NULL frequency means "all subscribers" (matches iOS semantics).
    frequency         INT,
    as_of_article_id  BIGINT,
    new_articles      INT  NOT NULL,
    sent_count        INT  NOT NULL DEFAULT 0,
    failed_count      INT  NOT NULL DEFAULT 0,
    title             TEXT,
    body              TEXT
);

CREATE INDEX IF NOT EXISTS idx_android_notif_dispatch_freq_at
    ON android_notification_dispatches(frequency, dispatched_at DESC);


-- Broadcast kill-switch for Android. Symmetric to `notify_applestore_apps`
-- (iOS production) and `test_notification` (iOS sandbox). FeatureFlagRepository
-- treats unknown names as `false`, so a typo can only fail closed.
INSERT INTO feature_flags (feature, is_enabled) VALUES
    ('notify_android', FALSE)
ON CONFLICT (feature) DO NOTHING;
