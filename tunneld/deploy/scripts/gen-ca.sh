#!/bin/sh
# Generates the internal tunnel CA (run ONCE by the operator). The output dir is mounted into
# tunneld at /ca. ca.Load rejects non-CA material, so the CA:TRUE + keyCertSign extensions are
# mandatory.
set -eu
OUT_DIR="${1:?usage: gen-ca.sh <out-dir>}"
umask 077
openssl ecparam -name prime256v1 -genkey -noout -out "$OUT_DIR/ca-key.pem"
openssl req -x509 -new -key "$OUT_DIR/ca-key.pem" -sha256 -days 3650 \
  -subj "/CN=tunneld-ca" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out "$OUT_DIR/ca.pem"
