-- Track the most recent article id pushed to each subscriber so future
-- dispatches can dedup or compute per-subscriber "what's new since last push".
-- Nullable: NULL means we've never pushed to this subscriber yet.
-- Intentionally not a FK — articles get cleaned up periodically and we don't
-- want a cascade to break subscriptions.

ALTER TABLE notification_subscriptions
    ADD COLUMN IF NOT EXISTS last_pushed_article_id BIGINT;
