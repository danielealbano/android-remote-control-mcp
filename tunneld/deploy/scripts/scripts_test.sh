#!/bin/sh
# POSIX test harness for the fetcher + gen-ca scripts (invoked by `make test-scripts`).
# Stubs `curl` via a PATH shim returning fixture bytes; asserts temp-then-mv and never-clobber.
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
FAILS=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILS=$((FAILS + 1)); }

# make_stub_curl <bindir>: a curl shim. Behavior via env:
#   STUB_CURL_EXIT (default 0): exit code (nonzero simulates a download failure).
#   STUB_CURL_SRC:              file whose bytes are copied to the `-o <target>` path.
make_stub_curl() {
  bindir="$1"
  mkdir -p "$bindir"
  cat > "$bindir/curl" <<'SHIM'
#!/bin/sh
out=""
prev=""
for a in "$@"; do
  if [ "$prev" = "-o" ]; then out="$a"; fi
  prev="$a"
done
if [ "${STUB_CURL_EXIT:-0}" != "0" ]; then exit "${STUB_CURL_EXIT}"; fi
if [ -n "$out" ] && [ -n "${STUB_CURL_SRC:-}" ]; then cp "$STUB_CURL_SRC" "$out"; fi
exit 0
SHIM
  chmod +x "$bindir/curl"
}

# --- droplist converts feed to cidr lines ---
t="$(mktemp -d)"
b="$(mktemp -d)"
make_stub_curl "$b"
cat > "$t/feed.json" <<'JSON'
{"type":"metadata","version":1}
{"cidr":"1.2.3.0/24","sblid":"SBL1"}
{"cidr":"5.6.0.0/16","sblid":"SBL2"}
JSON
if STUB_CURL_SRC="$t/feed.json" PATH="$b:$PATH" OUT_DIR="$t" sh "$DIR/fetch-droplist.sh" &&
  grep -q '^cidr 1.2.3.0/24$' "$t/droplist.bans" &&
  grep -q '^cidr 5.6.0.0/16$' "$t/droplist.bans" &&
  [ ! -f "$t/droplist.feed.tmp" ]; then
  pass "droplist converts feed to cidr lines"
else
  fail "droplist converts feed to cidr lines"
fi

# --- droplist failure preserves old file ---
t="$(mktemp -d)"
b="$(mktemp -d)"
make_stub_curl "$b"
echo "cidr 9.9.9.0/24" > "$t/droplist.bans"
STUB_CURL_EXIT=1 PATH="$b:$PATH" OUT_DIR="$t" sh "$DIR/fetch-droplist.sh" 2>/dev/null || true
if grep -q '^cidr 9.9.9.0/24$' "$t/droplist.bans"; then
  pass "droplist failure preserves old file"
else
  fail "droplist failure preserves old file"
fi

# --- dbip skips when month present ---
t="$(mktemp -d)"
b="$(mktemp -d)"
make_stub_curl "$b"
date -u +%Y-%m > "$t/dbip-country-lite.month"
# curl exits 7 if called; the skip path must exit 0 without calling it.
if STUB_CURL_EXIT=7 PATH="$b:$PATH" OUT_DIR="$t" sh "$DIR/fetch-dbip.sh"; then
  pass "dbip skips when month present"
else
  fail "dbip skips when month present (curl was called or script errored)"
fi

# --- dbip downloads when month missing ---
t="$(mktemp -d)"
b="$(mktemp -d)"
make_stub_curl "$b"
printf 'start_ip,end_ip,country\n1.0.0.0,1.0.0.255,XX\n' > "$t/dbip.csv"
gzip -c "$t/dbip.csv" > "$t/dbip.csv.gz"
if STUB_CURL_SRC="$t/dbip.csv.gz" PATH="$b:$PATH" OUT_DIR="$t" sh "$DIR/fetch-dbip.sh" &&
  [ -f "$t/dbip-country-lite.csv" ] &&
  [ -f "$t/dbip-country-lite.month" ] &&
  grep -q 'XX' "$t/dbip-country-lite.csv"; then
  pass "dbip downloads when month missing"
else
  fail "dbip downloads when month missing"
fi

# --- gen-ca produces a signing CA ---
t="$(mktemp -d)"
sh "$DIR/gen-ca.sh" "$t"
if openssl x509 -in "$t/ca.pem" -noout -text | grep -q 'CA:TRUE' &&
  openssl x509 -in "$t/ca.pem" -noout -text | grep -q 'Certificate Sign'; then
  pub_key="$(openssl pkey -in "$t/ca-key.pem" -pubout 2>/dev/null)"
  pub_crt="$(openssl x509 -in "$t/ca.pem" -pubkey -noout 2>/dev/null)"
  if [ "$pub_key" = "$pub_crt" ]; then
    pass "gen-ca produces a signing CA"
  else
    fail "gen-ca key does not match cert"
  fi
else
  fail "gen-ca missing CA:TRUE / keyCertSign"
fi

if [ "$FAILS" -ne 0 ]; then
  echo "$FAILS script test(s) failed"
  exit 1
fi
echo "all script tests passed"
