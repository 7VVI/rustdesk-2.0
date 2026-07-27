#!/bin/bash
set -o pipefail
export PATH="/opt/flutter/bin:$PATH" FLUTTER_ROOT=/opt/flutter
export ANDROID_SDK_ROOT=/opt/android-sdk ANDROID_HOME=/opt/android-sdk
git config --global --add safe.directory /opt/flutter >/dev/null 2>&1

cd /opt/rustdesk/rustdesk/flutter || exit 1

# Restore android/ no matter how we exit.
restore() {
  if [ -d android_hostapp_bak ] && [ ! -d android ]; then
    mv android_hostapp_bak android
    echo "[restore] android/ restored"
  fi
}
trap restore EXIT

if [ -d android ]; then
  mv android android_hostapp_bak
  echo "[build] moved android/ -> android_hostapp_bak"
fi

TARGET="${1:-android-arm64}"
echo "[build] flutter build aar target-platform=$TARGET"
flutter build aar --release --no-debug --no-profile --target-platform "$TARGET"
rc=$?
echo "[build] flutter build aar exit=$rc"
exit $rc
