#!/bin/sh
# Downloads the current-month DB-IP Country Lite CSV ONLY if this month's file is not already
# present (a monthly sentinel gates the network), decompresses it, and mv's it into place.
# Idempotent; safe at container start and via cron. NO pipelines (SC3040-safe). Atomic handoff.
#
# DB-IP Country Lite is CC-BY — the README carries the attribution line.
set -eu

OUT_DIR="${OUT_DIR:?OUT_DIR required}"
# Base URL (not a printf format — avoids shellcheck SC2059); the month suffix is appended below.
DBIP_URL_BASE="${DBIP_URL_BASE:-https://download.db-ip.com/free/}"

month="$(date -u +%Y-%m)"
sentinel="$OUT_DIR/dbip-country-lite.month"
target="$OUT_DIR/dbip-country-lite.csv"

if [ -f "$sentinel" ]; then
  cur="$(cat "$sentinel")"
  if [ "$cur" = "$month" ]; then
    exit 0
  fi
fi

url="${DBIP_URL_BASE}dbip-country-lite-${month}.csv.gz"
gz="$OUT_DIR/dbip-country-lite.csv.gz.tmp"
csvtmp="$OUT_DIR/dbip-country-lite.csv.tmp"

curl -fsS "$url" -o "$gz"
gunzip -c "$gz" > "$csvtmp"
mv "$csvtmp" "$target"
rm -f "$gz"
printf '%s' "$month" > "$sentinel"
