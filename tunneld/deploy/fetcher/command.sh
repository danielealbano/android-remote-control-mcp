#!/bin/sh
set -eu
apk add --no-cache curl jq
crontab - <<'CRON'
30 3 * * * OUT_DIR=/banfiles /scripts/fetch-droplist.sh
40 3 * * * OUT_DIR=/banfiles /scripts/fetch-dbip.sh
CRON
OUT_DIR=/banfiles /scripts/fetch-droplist.sh || true
OUT_DIR=/banfiles /scripts/fetch-dbip.sh || true
exec crond -f
