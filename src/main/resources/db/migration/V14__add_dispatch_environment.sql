-- Track each dispatch's APNs environment so prod and sandbox keep
-- independent per-frequency `as_of_article_id` markers. Without this,
-- a sandbox test push would bump the same marker prod uses for that
-- tier, silencing the very next prod push (newArticles=0).
--
-- Existing rows are production by definition — sandbox dispatch didn't
-- exist before this change.
ALTER TABLE notification_dispatches
    ADD COLUMN IF NOT EXISTS apns_environment TEXT NOT NULL DEFAULT 'production'
        CHECK (apns_environment IN ('production', 'sandbox'));

-- The hot lookup is now (env, freq, dispatched_at DESC) — find the most
-- recent dispatch for this env+tier combo to read its as_of_article_id.
-- The old (frequency, dispatched_at DESC) index becomes redundant.
DROP INDEX IF EXISTS idx_notif_dispatch_freq_at;
CREATE INDEX IF NOT EXISTS idx_notif_dispatch_env_freq_at
    ON notification_dispatches(apns_environment, frequency, dispatched_at DESC);
