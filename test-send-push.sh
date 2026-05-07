#!/usr/bin/env bash
#
# Sends a single APNs push to a real device, using the production codepath
# (PushyApnsMessagingService) but bypassing Vault, the DB, and the deployed
# pod. The whole stack runs locally from your laptop straight to Apple.
#
# Usage:
#   ./test-send-push.sh <DEVICE_TOKEN>
#   APNS_DEVICE_TOKEN=<token> ./test-send-push.sh
#
# Where to get DEVICE_TOKEN: run the iOS app from Xcode, look in the console for
#   [AppDelegate] APNs token received (len=64) hex=<64-hex-chars>
# Copy the value after `hex=`.
#
# Override defaults via env vars if you ever need to:
#   APNS_KEY_PATH, APNS_KEY_ID, APNS_TEAM_ID, APNS_BUNDLE_ID, APNS_SANDBOX
#
# `APNS_SANDBOX=true` is correct when the iPhone is running an Xcode-debug
# build (development signing → sandbox APNs token). Flip to `false` only when
# the device has a TestFlight / Ad Hoc / App Store build (distribution
# signing → production APNs token).

set -euo pipefail

# ── defaults ──────────────────────────────────────────────────────────────────
: "${APNS_KEY_PATH:=/Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-ios/AuthKey_NAGW4JNLSM.p8}"
: "${APNS_KEY_ID:=NAGW4JNLSM}"
: "${APNS_TEAM_ID:=7PB86SYNNM}"
: "${APNS_BUNDLE_ID:=com.emudoi.snelnieuws}"
: "${APNS_SANDBOX:=true}"
: "${APNS_DEVICE_TOKEN:=${1:-}}"

# ── input check ───────────────────────────────────────────────────────────────
if [[ -z "${APNS_DEVICE_TOKEN}" ]]; then
  echo "ERROR: device token missing." >&2
  echo "Usage: $0 <DEVICE_TOKEN>   (or set APNS_DEVICE_TOKEN env var)" >&2
  exit 1
fi
if [[ ! -f "${APNS_KEY_PATH}" ]]; then
  echo "ERROR: .p8 file not found at ${APNS_KEY_PATH}" >&2
  exit 1
fi

# ── run ───────────────────────────────────────────────────────────────────────
echo "→ keyId=${APNS_KEY_ID} teamId=${APNS_TEAM_ID} bundle=${APNS_BUNDLE_ID} sandbox=${APNS_SANDBOX} tokenPrefix=${APNS_DEVICE_TOKEN:0:8}"

export APNS_KEY_PATH APNS_KEY_ID APNS_TEAM_ID APNS_BUNDLE_ID APNS_SANDBOX APNS_DEVICE_TOKEN

cd "$(dirname "$0")"
exec sbt 'testOnly com.snelnieuws.service.SendTestPushSpec'
