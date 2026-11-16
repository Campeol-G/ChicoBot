#!/usr/bin/env bash
# Shell regression tests for bin/chicoBot. Run: bash tests/chicoBot-test.sh
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/bin/chicoBot"

tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  [[ "$1" == "$2" ]] || fail "expected '$2', got '$1'"
}

# --- env writer: quoting, comments, unknown keys and mode ---
ENV_FILE="$tmp/env"
CONFIG_DIR="$tmp"
cat > "$ENV_FILE" <<'EOF'
# personal comment
UNKNOWN_KEY=keep
DISCORD_TOKEN=old
EOF
chmod 600 "$ENV_FILE"

DISCORD_TOKEN="a'b\\c\$d\`e"
DEV_GUILD_ID=""
YT_REFRESH_TOKEN=""
YT_CIPHER_URL=""
YT_CIPHER_PASSWORD=""
SPOTIFY_CLIENT_ID=""
SPOTIFY_CLIENT_SECRET=""
SPOTIFY_REFRESH_TOKEN=""
APPLE_DEVELOPER_TOKEN=""
APPLE_STOREFRONT="us"
APPLE_USER_TOKEN=""
write_env

assert_eq "$(stat -c '%a' "$ENV_FILE")" "600"
grep -q '^# personal comment$' "$ENV_FILE" || fail "comment was not preserved"
grep -q '^UNKNOWN_KEY=keep$' "$ENV_FILE" || fail "unknown key was not preserved"
(
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  assert_value="$DISCORD_TOKEN"
  [[ "$assert_value" == "a'b\\c\$d\`e" ]] || exit 1
) || fail "quoted value did not survive sourcing"

# --- ensure_env repairs a legacy 644 env before start ---
chmod 644 "$ENV_FILE"
ensure_env >/dev/null 2>&1 || fail "ensure_env rejected a valid env"
assert_eq "$(stat -c '%a' "$ENV_FILE")" "600"

# --- optional prompts can keep or clear a suggested value ---
kept="$(printf '\n' | ask_optional "Cipher URL" "http://localhost:8001")"
assert_eq "$kept" "http://localhost:8001"
cleared="$(printf -- '-\n' | ask_optional "Cipher URL" "http://localhost:8001")"
assert_eq "$cleared" ""

# --- Java detection survives JAVA_TOOL_OPTIONS noise ---
(
  export JAVA_TOOL_OPTIONS=-Dchicobot.test=1
  major="$(java_major)"
  [[ "$major" =~ ^[0-9]+$ ]] || exit 1
  [[ "$major" -ge 25 ]] || exit 1
) || fail "java_major failed with JAVA_TOOL_OPTIONS set"

# --- start passes the resolved env path to run.sh ---
out="$(CHICOS_BOT_DRY_RUN=1 start)"
case "$out" in
  *"CHICOS_BOT_ENV=$ENV_FILE"*) ;;
  *) fail "start did not pass CHICOS_BOT_ENV: $out" ;;
esac

echo "chicoBot shell tests passed"
