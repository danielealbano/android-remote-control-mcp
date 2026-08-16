#!/bin/sh
# Downloads the current-month DB-IP Country Lite CSV ONLY if this month's file is not already
# present (a monthly sentinel gates the network), decompresses it, and mv's it into place.
# Idempotent; safe at container start and via cron. NO pipelines (SC3040-safe). Atomic handoff.
#
# DB-IP Country Lite is CC-BY — the README carries the attribution line.
set -eu

OUT_DIR="${OUT_DIR:?OUT_DIR required}"
# Month-versioned template with a literal `%s` placeholder for the YYYY-MM month. The URL is built
# by POSIX parameter-expansion substitution (NOT printf with a variable format string — that would
# trip shellcheck SC2059), so the operator-supplied template stays fully honoured.
DBIP_URL_TEMPLATE="${DBIP_URL_TEMPLATE:-https://download.db-ip.com/free/dbip-country-lite-%s.csv.gz}"

month="$(date -u +%Y-%m)"
sentinel="$OUT_DIR/dbip-country-lite.month"
target="$OUT_DIR/dbip-country-lite.csv"

if [ -f "$sentinel" ]; then
  cur="$(cat "$sentinel")"
  if [ "$cur" = "$month" ]; then
    exit 0
  fi
fi

# Substitute the month for the first `%s` in the template (SC2059-safe; no printf, no pipeline).
url_prefix="${DBIP_URL_TEMPLATE%%%s*}"
url_suffix="${DBIP_URL_TEMPLATE#*%s}"
url="${url_prefix}${month}${url_suffix}"
gz="$OUT_DIR/dbip-country-lite.csv.gz.tmp"
csvtmp="$OUT_DIR/dbip-country-lite.csv.tmp"

curl -fsS "$url" -o "$gz"
gunzip -c "$gz" > "$csvtmp"
mv "$csvtmp" "$target"
rm -f "$gz"
printf '%s' "$month" > "$sentinel"
