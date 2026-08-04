#!/bin/bash
# Assemble a standalone, self-contained RDSDK distribution directory that an
# external Android app can consume WITHOUT the rustdesk source tree.
#
# Output layout (DIST):
#   rdsdk-dist/
#   ├── libs/rdsdk-release.aar          # the SDK (public API + controlled-end + 3-abi librustdesk.so)
#   ├── m2repository/                   # local maven repo (flutter engine/dart/plugins + rustls)
#   ├── samples/DemoActivity.kt         # integration example (passes server info)
#   └── README.md                       # integration guide
set -euo pipefail

RUST_ROOT="/opt/rustdesk/rustdesk"
FLUTTER="${RUST_ROOT}/flutter"
DIST="/opt/rustdesk/rdsdk-dist"

RDSDK_AAR="${FLUTTER}/build/rdsdk/outputs/aar/rdsdk-release.aar"
FLUTTER_REPO="${FLUTTER}/build/host/outputs/repo"

# Locate the rustls-platform-verifier maven repo via cargo metadata.
RUSTLS_MAVEN="$(cd "${RUST_ROOT}" && cargo metadata --format-version 1 2>/dev/null \
  | python3 -c "import sys,json,os; d=json.load(sys.stdin); p=[x for x in d['packages'] if x['name']=='rustls-platform-verifier-android'][0]; print(os.path.join(os.path.dirname(p['manifest_path']),'maven'))")"

echo "[pkg] RDSDK_AAR    = ${RDSDK_AAR}"
echo "[pkg] FLUTTER_REPO = ${FLUTTER_REPO}"
echo "[pkg] RUSTLS_MAVEN = ${RUSTLS_MAVEN}"

# --- sanity checks ---
[ -f "${RDSDK_AAR}" ]        || { echo "[pkg] ERROR: rdsdk-release.aar not found. Build it first."; exit 1; }
[ -d "${FLUTTER_REPO}" ]     || { echo "[pkg] ERROR: flutter host repo not found. Run 'flutter build aar' first."; exit 1; }
[ -d "${RUSTLS_MAVEN}/rustls" ] || { echo "[pkg] ERROR: rustls maven repo not found."; exit 1; }

# --- (re)create dist ---
rm -rf "${DIST}"
mkdir -p "${DIST}/libs" "${DIST}/m2repository" "${DIST}/samples"

# 1) SDK aar
cp -f "${RDSDK_AAR}" "${DIST}/libs/rdsdk-release.aar"

# 2) flutter engine/dart/plugins maven repo
cp -a "${FLUTTER_REPO}/." "${DIST}/m2repository/"

# 3) merge rustls into the same local maven repo
cp -a "${RUSTLS_MAVEN}/rustls" "${DIST}/m2repository/rustls"

# 4) sample
cp -f "${FLUTTER}/android/demohost/src/main/kotlin/com/example/rddemo/MainActivity.kt" \
      "${DIST}/samples/DemoActivity.kt"

echo "[pkg] done. Contents:"
du -sh "${DIST}" "${DIST}/libs" "${DIST}/m2repository" 2>/dev/null
echo "[pkg] librustdesk abis in rdsdk aar:"
unzip -l "${DIST}/libs/rdsdk-release.aar" | grep 'librustdesk.so' | awk '{print $4}'
