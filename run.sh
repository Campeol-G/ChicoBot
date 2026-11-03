#!/usr/bin/env bash
# Runs the bot with the token loaded from the env file. Ctrl+C stops it.
set -euo pipefail

ENV_FILE="${CHICOS_BOT_ENV:-$HOME/.config/chicos-bot/env}"
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

exec java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -jar "$JAR"
