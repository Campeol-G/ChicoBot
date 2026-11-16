#!/usr/bin/env bash
# Repeatable OCR count report. Prints only theme,image,track-count rows; never
# OCR text. Usage:
#   scripts/ocr-eval.sh <references-dir> [--dark-otsu] [--include-bad]
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
JAR="$REPO_ROOT/target/chicos-bot.jar"
DARK=false
INCLUDE_BAD=false

usage() {
  cat <<'EOF'
Usage: scripts/ocr-eval.sh <references-dir> [--dark-otsu] [--include-bad]

Runs the production OCR pipeline over the reference screenshots and prints
theme,image,track-count rows plus a total. It never prints OCR text.
The baseline is dark Otsu off; --dark-otsu enables the measured candidate.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

[[ $# -ge 1 ]] || { usage; exit 2; }
ROOT="$1"
shift
for arg in "$@"; do
  case "$arg" in
    --dark-otsu) DARK=true ;;
    --include-bad) INCLUDE_BAD=true ;;
    *) usage; die "unknown option: $arg" ;;
  esac
done
[[ -d "$ROOT" ]] || die "not a directory: $ROOT"

if [[ ! -f "$JAR" ]]; then
  echo "building $JAR..." >&2
  ( cd "$REPO_ROOT" && mvn -q package -DskipTests )
fi

images() {
  local dir="$1"
  [[ -d "$dir" ]] || return 0
  find "$dir" -maxdepth 1 -type f \
    \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \) |
    sort
}

total=0
printf 'theme,image,tracks\n'
for theme in dark_theme white_theme; do
  theme_total=0
  while IFS= read -r image; do
    [[ -n "$image" ]] || continue
    if count="$(
      OCR_BINARIZE_DARK="$DARK" \
        java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true \
        -cp "$JAR" dev.chico.bot.ocr.OcrCli "$image" 2>/dev/null | wc -l
    )"; then
      count="${count//[[:space:]]/}"
    else
      count="ERR"
    fi
    printf '%s,%s,%s\n' "$theme" "$(basename "$image")" "$count"
    if [[ "$count" != "ERR" ]]; then
      theme_total=$((theme_total + count))
      total=$((total + count))
    fi
  done < <(images "$ROOT/$theme")

  if [[ "$INCLUDE_BAD" == "true" ]]; then
    while IFS= read -r image; do
      [[ -n "$image" ]] || continue
      if count="$(
        OCR_BINARIZE_DARK="$DARK" \
          java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true \
          -cp "$JAR" dev.chico.bot.ocr.OcrCli "$image" 2>/dev/null | wc -l
      )"; then
        count="${count//[[:space:]]/}"
      else
        count="ERR"
      fi
      printf '%s,%s,%s\n' "$theme/bad_prints" "$(basename "$image")" "$count"
      if [[ "$count" != "ERR" ]]; then
        theme_total=$((theme_total + count))
        total=$((total + count))
      fi
    done < <(images "$ROOT/$theme/bad_prints")
  fi
  printf 'THEME_TOTAL,%s,%s\n' "$theme" "$theme_total"
done

printf 'TOTAL,tracks,%s\n' "$total"
