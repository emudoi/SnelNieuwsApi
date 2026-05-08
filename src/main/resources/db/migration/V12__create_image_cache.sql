-- Mapping from external image URLs to cached files on the NFS-backed
-- /data/images volume. One row per unique source URL — content addressing
-- (sha256 of source_url) makes relative_path a deterministic function of
-- source_url, but we still record the row to track download state, retries,
-- and to drive the cleanup scheduler without scanning the filesystem.
--
-- status:
--   'downloaded' — bytes are on NFS at relative_path
--   'failed'     — last attempt failed; retry-eligibility is decided by
--                  ImageCacheService based on (attempts, last_attempt_at)
CREATE TABLE IF NOT EXISTS image_cache (
    source_url      TEXT PRIMARY KEY,
    relative_path   TEXT NOT NULL UNIQUE,
    content_type    TEXT,
    size_bytes      BIGINT,
    status          TEXT NOT NULL,
    downloaded_at   TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempts        INT NOT NULL DEFAULT 0
);

-- Cleanup scheduler walks rows by downloaded_at to find expired files.
CREATE INDEX IF NOT EXISTS idx_image_cache_downloaded_at
    ON image_cache(downloaded_at);
