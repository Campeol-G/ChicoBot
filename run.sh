#!/usr/bin/env bash
# Runs the bot with the token loaded from the env file. Ctrl+C stops it.
# If YT_CIPHER_URL points at localhost, the local yt-cipher stack is started
# first (it must live next to this repo as ../yt-cipher).
set -euo pipefail

ENV_FILE="${CHICOS_BOT_ENV:-$HOME/.config/chicos-bot/env}"
CIPHER_DIR="${CHICOS_CIPHER_DIR:-$(dirname "$0")/../yt-cipher}"
JAR="$(dirname "$0")/target/chicos-bot.jar"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "error: env file not found at $ENV_FILE" >&2
  echo "create it from .env.example (chmod 600) and set DISCORD_TOKEN" >&2
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "error: $JAR not found; run 'mvn -q package -DskipTests' first" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

ensure_cipher() {
  case "${YT_CIPHER_URL:-}" in
    http://localhost:*|http://127.0.0.1:*)
      ;;
    *)
      return 0
      ;;
  esac
  if curl -s -m 3 -o /dev/null "$YT_CIPHER_URL/" 2>/dev/null; then
    return 0
  fi
  echo "cipher at $YT_CIPHER_URL is down, starting it..."
  if [[ ! -d "$CIPHER_DIR" ]]; then
    echo "warning: cipher dir $CIPHER_DIR missing; YouTube playback will fail" >&2
    return 0
  fi
  docker compose -f "$CIPHER_DIR/docker-compose.yml" up -d >/dev/null
  for _ in $(seq 1 30); do
    if curl -s -m 3 -o /dev/null "$YT_CIPHER_URL/" 2>/dev/null; then
      echo "cipher is up"
      return 0
    fi
    sleep 2
  done
  echo "warning: cipher did not answer in time; YouTube playback will fail" >&2
}

ensure_cipher

exec java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -jar "$JAR"
