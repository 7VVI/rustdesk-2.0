#!/bin/bash
set -o pipefail
export PATH="/opt/flutter/bin:$PATH" FLUTTER_ROOT=/opt/flutter
export ANDROID_SDK_ROOT=/opt/android-sdk ANDROID_HOME=/opt/android-sdk
export FLUTTER_STORAGE_BASE_URL="https://storage.googleapis.com"
git config --global --add safe.directory /opt/flutter >/dev/null 2>&1

cd /opt/rustdesk/rustdesk/flutter/android || exit 1
echo "[demohost] ./gradlew :demohost:assembleRelease"
./gradlew :demohost:assembleRelease --stacktrace
rc=$?
echo "[demohost] gradlew exit=$rc"
exit $rc
