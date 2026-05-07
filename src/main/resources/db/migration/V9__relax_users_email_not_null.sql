-- Allow users rows to exist without an email. Needed so that
-- /notifications/subscribe can self-heal: when iOS presents a verified
-- token but the users row is missing (e.g. POST /users failed during a
-- brief backend outage at signup time), the subscribe handler inserts a
-- minimal users row keyed by uid only. Email is filled in later by the
-- next /users upsert from iOS.
--
-- Without this, a transient failure of POST /users during signup would
-- permanently leave the user unable to subscribe (FK violation on every
-- attempt) until they logged out and back in.

ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
