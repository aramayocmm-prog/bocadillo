#!/usr/bin/env bash
# Regenerates app/src/main/assets/noto from pinned upstream sources (needs git + python3).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
git clone -q --depth 1 --branch v2026-09-24-unicode18_0 --filter=blob:none --sparse https://github.com/googlefonts/noto-emoji "$TMP/noto"
git -C "$TMP/noto" -c gc.auto=0 sparse-checkout set 2D/svg third_party/region-flags
git clone -q --depth 1 https://github.com/googlefonts/emoji-metadata "$TMP/meta"
python3 "$ROOT/tools/noto/build_noto_pack.py" "$TMP/noto" "$TMP/meta" "$ROOT/app/src/main/assets/noto"
rm -rf "$TMP"
