#!/usr/bin/env bash
# Runs the XDRIVE configs the app writes (XDriveFmtTest drops them in build/xdrive-e2e) through
# the same Xray core the app ships, against a real WebDAV server, and checks real traffic.
# Usage: run.sh <xray binary> <fixture dir>
set -euo pipefail

XRAY=$1
FIXTURES=$2
WORK=$(mktemp -d)
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$WORK"' EXIT

# the server half of the throwaway pair in XDriveFmtTest
DECRYPTION="mlkem768x25519plus.native.600s.QCw7fS515SLtplSeXkxNIA-19NaFvFD1jFlFwz85LkY"
ID="5f0c3a2e-8d21-4b7e-9a51-2c6f0e9b7d10"

"$XRAY" version | sed -n 1p

mkdir -p "$WORK/www"
head -c 3000000 /dev/urandom > "$WORK/www/blob.bin"
( cd "$WORK/www" && exec python3 -m http.server 18080 --bind 127.0.0.1 ) > /dev/null 2>&1 &

mkdir -p "$WORK/dav/dav/tunnel"
: > "$WORK/dav.log"
python3 "$(dirname "$0")/davserver.py" "$WORK/dav" 18090 jmc kite-pass "$WORK/dav.log" &

for _ in $(seq 1 30); do
  curl -s --noproxy '*' -o /dev/null http://127.0.0.1:18080/ && \
  curl -s --noproxy '*' -o /dev/null -u jmc:kite-pass -X PROPFIND -H 'Depth: 1' http://127.0.0.1:18090/dav/tunnel/ && break
  sleep 1
done

# the server uses the client's own xdriveSettings, so both ends read the same storage
python3 - "$FIXTURES/form_webdav.json" "$WORK/server.json" "$ID" "$DECRYPTION" <<'PY'
import json, sys
client, out, uid, dec = sys.argv[1:]
stream = next(o for o in json.load(open(client))["outbounds"] if o.get("tag") == "proxy")["streamSettings"]
json.dump({
    "log": {"loglevel": "warning"},
    "inbounds": [{"tag": "drive", "listen": "127.0.0.1", "port": 20001, "protocol": "vless",
                  "settings": {"clients": [{"id": uid}], "decryption": dec},
                  "streamSettings": stream}],
    # the test target is on loopback, which freedom blocks for VLESS by default
    "outbounds": [{"protocol": "freedom", "settings": {"finalRules": [{"action": "allow"}]}}],
}, open(out, "w"), indent=1)
PY
"$XRAY" run -test -c "$WORK/server.json"
"$XRAY" run -c "$WORK/server.json" > "$WORK/server.log" 2>&1 &
SERVER=$!

# negative control: the panel's config as shipped must be refused for the reason the repair fixes
if "$XRAY" run -test -c "$FIXTURES/panel_nomux.raw.json" > "$WORK/raw.log" 2>&1; then
  echo "FAIL: the core accepted an XDRIVE config without mux, the repair would prove nothing"; exit 1
fi
grep -q 'the xdrive transport needs "mux" enabled' "$WORK/raw.log" || { cat "$WORK/raw.log"; echo "FAIL: refused for another reason"; exit 1; }
echo "ok: unrepaired panel config refused: needs mux"

check() {
  local name=$1 config=$2
  "$XRAY" run -test -c "$config"
  "$XRAY" run -c "$config" > "$WORK/$name.log" 2>&1 &
  local client=$!
  sleep 2
  local before
  before=$(grep -c '"PUT ' "$WORK/dav.log" || true)
  # several rounds, since a storage that shows half-written uploads fails only now and then
  for round in 1 2 3 4 5; do
    curl -sS --noproxy '' -m 120 --socks5-hostname 127.0.0.1:10808 -o "$WORK/got.bin" \
      -w "$name #$round: http=%{http_code} first_byte=%{time_starttransfer}s total=%{time_total}s\n" \
      http://127.0.0.1:18080/blob.bin || { tail -n 20 "$WORK/$name.log" "$WORK/server.log" "$WORK/dav.log"; exit 1; }
    cmp "$WORK/got.bin" "$WORK/www/blob.bin" || { echo "FAIL: $name delivered different bytes"; exit 1; }
  done
  local puts
  puts=$(( $(grep -c '"PUT ' "$WORK/dav.log" || true) - before ))
  [ "$puts" -gt 0 ] || { echo "FAIL: $name moved no files through WebDAV"; exit 1; }
  echo "ok: $name delivered 5 x 3 MB byte-for-byte through $puts WebDAV uploads"
  kill "$client"; wait "$client" 2>/dev/null || true
  rm -f "$WORK/got.bin"
}

check form "$FIXTURES/form_webdav.json"
check panel-repaired "$FIXTURES/panel_nomux.repaired.json"

kill "$SERVER"
echo "XDRIVE end-to-end: all checks passed"
