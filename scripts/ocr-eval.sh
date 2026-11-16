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
The directory must contain dark_theme/ and white_theme/; bad_prints/ is
included only with --include-bad.
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
for theme in dark_theme white_theme; do
  [[ -d "$ROOT/$theme" ]] || die "missing $ROOT/$theme"
done

if [[ ! -f "$JAR" ]] ||
  find "$REPO_ROOT/src" "$REPO_ROOT/pom.xml" -newer "$JAR" -print -quit | grep -q .; then
  echo "building $JAR..." >&2
  ( cd "$REPO_ROOT" && mvn -q package -DskipTests )
fi

echo "ocr_eval: dark_otsu=$DARK binarize=false psm=6 langs=por+eng" >&2

images() {
  local dir="$1"
  [[ -d "$dir" ]] || return 0
  find "$dir" -maxdepth 1 -type f \
    \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \) |
    sort
}

errors=0
images_seen=0
total=0

run_image() {
  local theme="$1" image="$2" count
  if count="$(
    OCR_BINARIZE=false OCR_BINARIZE_DARK="$DARK" OCR_PSM=6 OCR_LANGS=por+eng \
      java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true \
      -cp "$JAR" dev.chico.bot.ocr.OcrCli "$image" 2>/dev/null | wc -l
  )"; then
    count="${count//[[:space:]]/}"
  else
    count="ERR"
  fi
  printf '%s,%s,%s\n' "$theme" "$(basename "$image")" "$count"
  images_seen=$((images_seen + 1))
  if [[ "$count" == "ERR" ]]; then
    errors=$((errors + 1))
  else
    theme_total=$((theme_total + count))
    total=$((total + count))
  fi
}

printf 'theme,image,tracks\n'
for theme in dark_theme white_theme; do
  theme_total=0
  theme_images=0
  while IFS= read -r image; do
    [[ -n "$image" ]] || continue
    theme_images=$((theme_images + 1))
    run_image "$theme" "$image"
  done < <(images "$ROOT/$theme")

  if [[ "$INCLUDE_BAD" == "true" ]]; then
    while IFS= read -r image; do
      [[ -n "$image" ]] || continue
      theme_images=$((theme_images + 1))
      run_image "$theme/bad_prints" "$image"
    done < <(images "$ROOT/$theme/bad_prints")
  fi

  if [[ "$theme_images" -eq 0 ]]; then
    echo "warning: no images in $ROOT/$theme" >&2
    errors=$((errors + 1))
  fi
  printf 'THEME_TOTAL,%s,%s\n' "$theme" "$theme_total"
done

if [[ "$images_seen" -eq 0 ]]; then
  die "no images found under $ROOT"
fi
printf 'TOTAL,tracks,%s\n' "$total"
if [[ "$errors" -gt 0 ]]; then
  echo "error: $errors image(s) failed or were missing" >&2
  exit 1
fi
