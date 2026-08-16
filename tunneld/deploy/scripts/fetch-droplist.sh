#!/bin/sh
# Downloads the Spamhaus DROP JSON-lines feed and converts it to `cidr <prefix>` ban-file lines.
# NO pipelines (each stage writes a temp file) so plain `set -e` catches every failure and pipefail
# — undefined in POSIX sh (shellcheck SC3040) — is never needed. Atomic handoff: temp file + mv.
set -eu

OUT_DIR="${OUT_DIR:?OUT_DIR required}"
DROP_URL="${DROP_URL:-https://www.spamhaus.org/drop/drop_v4.json}"

feed="$OUT_DIR/droplist.feed.tmp"
tmp="$OUT_DIR/droplist.bans.tmp"

# On any curl failure `set -e` aborts here, leaving the previous droplist.bans untouched.
curl -fsS "$DROP_URL" -o "$feed"

# jq reads the feed file directly (no pipeline); select only records carrying a cidr.
jq -r 'select(.cidr) | "cidr \(.cidr)"' "$feed" > "$tmp"

mv "$tmp" "$OUT_DIR/droplist.bans"
rm -f "$feed"
