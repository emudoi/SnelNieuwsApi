-- Per-install rotation history for the personalised feed feature. Capped at
-- 1000 ids on write inside AppClientRepository.appendServedIds. Stale ids
-- referencing deleted articles are harmless (cap-on-write bounds cost) and
-- are not actively pruned.
ALTER TABLE app_clients
  ADD COLUMN IF NOT EXISTS last_served_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
