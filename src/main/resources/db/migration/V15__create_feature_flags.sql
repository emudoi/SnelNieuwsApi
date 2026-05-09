-- Runtime kill-switches for the broadcast endpoint. Flipping `is_enabled`
-- with a single UPDATE turns each environment's broadcast path on or off
-- without a redeploy:
--
--   test_notification       → guards POST /notifications/broadcast → sandbox
--                             (Xcode-debug installs)
--   notify_applestore_apps  → guards POST /notifications/broadcast → production
--                             (App Store + TestFlight)
--
-- An unknown feature name is treated as `false` by the repo, so a typo in
-- code can only fail closed.
CREATE TABLE IF NOT EXISTS feature_flags (
    id         BIGSERIAL PRIMARY KEY,
    feature    TEXT    NOT NULL UNIQUE,
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO feature_flags (feature, is_enabled) VALUES
    ('test_notification',       FALSE),
    ('notify_applestore_apps',  FALSE)
ON CONFLICT (feature) DO NOTHING;
