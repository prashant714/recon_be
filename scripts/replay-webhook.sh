#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE_ROOT="$ROOT_DIR/src/test/resources/webhook-fixtures"

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
RAZORPAY_SECRET="${RAZORPAY_WEBHOOK_SECRET:-razorpay_webhook_secret}"
STRIPE_SECRET="${STRIPE_WEBHOOK_SECRET:-whsec_test_secret}"

usage() {
  cat <<'EOF'
Usage:
  scripts/replay-webhook.sh <provider> <event>
  scripts/replay-webhook.sh <provider> --file <absolute-or-relative-json-path>
  scripts/replay-webhook.sh --list

Examples:
  scripts/replay-webhook.sh razorpay payment.captured
  scripts/replay-webhook.sh stripe payment_intent.succeeded
  BASE_URL=http://127.0.0.1:8080 scripts/replay-webhook.sh stripe charge.refunded

Environment overrides:
  BASE_URL
  RAZORPAY_WEBHOOK_SECRET
  STRIPE_WEBHOOK_SECRET
EOF
}

list_fixtures() {
  echo "Razorpay fixtures:"
  (cd "$FIXTURE_ROOT/razorpay" && ls -1 *.json | sed 's/\.json$//')
  echo
  echo "Stripe fixtures:"
  (cd "$FIXTURE_ROOT/stripe" && ls -1 *.json | sed 's/\.json$//')
}

require_file() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo "Fixture not found: $file" >&2
    exit 1
  fi
}

hex_hmac() {
  local secret="$1"
  local file="$2"
  openssl dgst -sha256 -hmac "$secret" "$file" | awk '{print $NF}'
}

stripe_signature() {
  local secret="$1"
  local file="$2"
  local timestamp sig tmpfile
  timestamp="$(date +%s)"
  tmpfile="$(mktemp)"
  printf '%s.' "$timestamp" > "$tmpfile"
  cat "$file" >> "$tmpfile"
  sig="$(openssl dgst -sha256 -hmac "$secret" "$tmpfile" | awk '{print $NF}')"
  rm -f "$tmpfile"
  printf 't=%s,v1=%s' "$timestamp" "$sig"
}

if [[ "${1:-}" == "--list" ]]; then
  list_fixtures
  exit 0
fi

if [[ $# -lt 2 ]]; then
  usage
  exit 1
fi

provider="$1"
shift

case "$provider" in
  razorpay|stripe) ;;
  *)
    echo "Unsupported provider: $provider" >&2
    usage
    exit 1
    ;;
esac

if [[ "${1:-}" == "--file" ]]; then
  if [[ $# -ne 2 ]]; then
    usage
    exit 1
  fi
  fixture_path="$2"
  if [[ ! "$fixture_path" = /* ]]; then
    fixture_path="$ROOT_DIR/$fixture_path"
  fi
  event_name="$(basename "$fixture_path" .json)"
else
  event_name="$1"
  fixture_path="$FIXTURE_ROOT/$provider/$event_name.json"
fi

require_file "$fixture_path"

case "$provider" in
  razorpay)
    endpoint="$BASE_URL/webhooks/razorpay"
    signature="$(hex_hmac "$RAZORPAY_SECRET" "$fixture_path")"
    header_name="X-Razorpay-Signature"
    ;;
  stripe)
    endpoint="$BASE_URL/webhooks/stripe"
    signature="$(stripe_signature "$STRIPE_SECRET" "$fixture_path")"
    header_name="Stripe-Signature"
    ;;
esac

echo "Replaying $provider/$event_name"
echo "Fixture: $fixture_path"
echo "POST $endpoint"

curl -i \
  -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -H "$header_name: $signature" \
  --data-binary "@$fixture_path"
