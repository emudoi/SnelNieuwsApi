-- Subscriptions for push notifications. Keyed on the iOS-side stable
-- device_id (a UUID generated once on the device) so re-installs that
-- mint a new fcm_token can update in place. Frequency is the user's
-- preferred number of pushes per day (1–4); Airflow owns the schedule
-- and calls POST /notifications/dispatch with the appropriate filter.

CREATE TABLE IF NOT EXISTS notification_subscriptions (
    device_id   VARCHAR(64) PRIMARY KEY,
    fcm_token   TEXT NOT NULL,
    frequency   INT  NOT NULL CHECK (frequency BETWEEN 1 AND 4),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notif_sub_frequency
    ON notification_subscriptions(frequency);
