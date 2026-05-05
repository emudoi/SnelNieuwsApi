-- Audit + state table for notification dispatches.
--
-- One row per call to POST /notifications/dispatch (regardless of whether
-- anything was actually sent — a no-op dispatch is still recorded).
--
-- The most recent row per `frequency` is the source of truth for "what was
-- the highest article id we've already pushed about for this tier?". The
-- next dispatch counts articles with id > that value to decide what's new.
--
-- `frequency` is NULL when dispatch was called without the filter (i.e. fan
-- out to every subscriber); per-frequency tracking is independent from that.

CREATE TABLE IF NOT EXISTS notification_dispatches (
    id                BIGSERIAL PRIMARY KEY,
    dispatched_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frequency         INT,
    as_of_article_id  BIGINT,
    new_articles      INT NOT NULL,
    sent_count        INT NOT NULL DEFAULT 0,
    failed_count      INT NOT NULL DEFAULT 0,
    title             TEXT,
    body              TEXT
);

CREATE INDEX IF NOT EXISTS idx_notif_dispatch_freq_at
    ON notification_dispatches(frequency, dispatched_at DESC);

-- The per-subscriber column added in V5 is now redundant: tracking lives in
-- notification_dispatches (global per frequency) rather than per device.
ALTER TABLE notification_subscriptions
    DROP COLUMN IF EXISTS last_pushed_article_id;
