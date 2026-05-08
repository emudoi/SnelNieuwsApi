-- Per-install device identity. Issued once per app install when the iOS
-- app first hits POST /v2/clients/register, then sent on every later v2
-- request as `X-Client-Key: <client_id>`. The v2 servlet's before() filter
-- rejects requests whose key is missing, unknown, or has a non-NULL
-- revoked_at — independent of (and layered alongside) Firebase auth.
--
-- Lifetime is tied to the iOS Keychain entry, which survives app
-- uninstall by default — so the same client_id reappears across reinstalls
-- on the same device. That's deliberate: it gives us a stable per-device
-- identity for rate-limiting and revocation without depending on the
-- volatile UserDefaults-backed device_id.

CREATE TABLE IF NOT EXISTS app_clients (
    client_id      UUID PRIMARY KEY,
    bundle_id      TEXT NOT NULL,
    os_version     TEXT,
    platform       TEXT NOT NULL,
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_app_clients_active
    ON app_clients(client_id) WHERE revoked_at IS NULL;

-- Link subscription rows back to the install that produced them. NULL for
-- rows created before this column existed (and for any v1 traffic, which
-- never sends X-Client-Key). ON DELETE SET NULL so revoking a client never
-- silently wipes the user's subscriptions — they just lose the install link.
ALTER TABLE notification_subscriptions
    ADD COLUMN IF NOT EXISTS client_id UUID NULL
        REFERENCES app_clients(client_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_notif_sub_client_id
    ON notification_subscriptions(client_id);
