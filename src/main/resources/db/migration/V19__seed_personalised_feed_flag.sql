-- Default off. Phase 8 (human-operated) flips it on in production via a
-- direct SQL UPDATE on the feature_flags table.
INSERT INTO feature_flags (feature, is_enabled)
VALUES ('personalised_feed_enabled', false)
ON CONFLICT (feature) DO NOTHING;
