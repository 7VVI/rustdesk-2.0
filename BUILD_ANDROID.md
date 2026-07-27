# RustDesk Android 编译指南

本文档记录了在 Ubuntu Linux 环境下编译 RustDesk Android 客户端的完整步骤。

## 环境要求

### 系统要求
- Ubuntu 24.04 LTS (或其他 Linux 发行版)
- 至少 16GB 内存
- 至少 50GB 可用磁盘空间
- 稳定的网络连接(需要访问 GitHub 和 Google 服务)

### 基础工具
```bash
# 安装基础依赖
sudo apt update
sudo apt install -y build-essential git curl wget unzip zip \
    clang cmake ninja-build pkg-config libssl-dev \
    nasm yasm libgtk-3-dev
```

## 1. 安装 Rust 环境

```bash
# 安装 Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# 验证安装
rustc --version
cargo --version

# 安装 Android 编译目标
rustup target add aarch64-linux-android armv7-linux-androideabi

# 安装 cargo-ndk (用于交叉编译)
cargo install cargo-ndk
```

## 2. 安装 Android SDK 和 NDK

### 方法一:使用 Android SDK Manager (推荐)

```bash
# 创建 Android SDK 目录
sudo mkdir -p /opt/android-sdk
sudo chown $USER:$USER /opt/android-sdk

# 下载 cmdline-tools
cd /opt/android-sdk
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mkdir -p cmdline-tools/latest
mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/latest/ 2>/dev/null || true

# 设置环境变量
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 接受许可协议
yes | sdkmanager --licenses

# 安装必要的 SDK 组件
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
sdkmanager "ndk;25.2.9519653"

# 验证安装
adb --version
sdkmanager --version
```

### 方法二:手动下载 NDK

```bash
# 下载 NDK r25c (版本 25.2.9519653)
cd /opt
wget https://dl.google.com/android/repository/android-ndk-r25c-linux.zip
unzip android-ndk-r25c-linux.zip
mv android-ndk-r25c /opt/android-sdk/ndk/25.2.9519653

# 设置环境变量
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/25.2.9519653
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
```

### 持久化环境变量

```bash
# 添加到 ~/.bashrc 或 ~/.zshrc
cat >> ~/.bashrc << 'EOF'

# Android SDK
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/25.2.9519653
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
EOF

source ~/.bashrc
```

## 3. 安装 vcpkg

vcpkg 用于编译 Android 需要的 C/C++ 依赖库。

```bash
# 克隆 vcpkg
cd /opt
git clone https://github.com/microsoft/vcpkg.git
cd vcpkg

# 编译 vcpkg
./bootstrap-vcpkg.sh

# 设置环境变量
export VCPKG_ROOT=/opt/vcpkg
export PATH=$PATH:$VCPKG_ROOT

# 持久化
echo 'export VCPKG_ROOT=/opt/vcpkg' >> ~/.bashrc
echo 'export PATH=$PATH:$VCPKG_ROOT' >> ~/.bashrc
```

## 4. 安装 Flutter

```bash
# 克隆 Flutter
cd /opt
git clone https://github.com/flutter/flutter.git -b stable
export PATH=$PATH:/opt/flutter/bin

# 安装 Flutter 依赖
flutter doctor

# 接受 Android 许可
flutter doctor --android-licenses

# 持久化
echo 'export PATH=$PATH:/opt/flutter/bin' >> ~/.bashrc
```

## 5. 编译 Android 依赖库

### 使用 vcpkg 编译 C/C++ 依赖

```bash
cd /opt/rustdesk/rustdesk/flutter

# 设置环境变量
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/25.2.9519653
export VCPKG_ROOT=/opt/vcpkg

# 编译 arm64-v8a 依赖 (约 10-15 分钟)
bash build_android_deps.sh arm64-v8a

# 可选:编译 armeabi-v7a 依赖
# bash build_android_deps.sh armeabi-v7a
```

编译的依赖包括:
- libvpx (VP8/VP9 视频编解码)
- libyuv (图像格式转换)
- opus (音频编解码)
- aom (AV1 视频编解码)
- ffmpeg (音视频处理)
- libjpeg-turbo (JPEG 编解码)

## 6. 生成 Flutter Rust Bridge

RustDesk 使用 Flutter Rust Bridge 连接 Rust 和 Flutter 代码。

```bash
# 安装 flutter_rust_bridge_codegen
cargo install flutter_rust_bridge_codegen --version 1.80.1 --features uuid --locked

# 进入 Flutter 目录
cd /opt/rustdesk/rustdesk/flutter

# 安装 Flutter 依赖
flutter pub get

# 生成 Bridge 代码
~/.cargo/bin/flutter_rust_bridge_codegen \
  --rust-input ../src/flutter_ffi.rs \
  --dart-output ./lib/generated_bridge.dart \
  --c-output ./android/app/src/main/cpp/bridge_generated.h
```

生成的文件:
- `src/bridge_generated.rs` - Rust 端绑定
- `src/bridge_generated.io.rs` - IO 相关绑定
- `flutter/lib/generated_bridge.dart` - Dart 端绑定
- `flutter/android/app/src/main/cpp/bridge_generated.h` - C 头文件

## 7. 编译 Rust Android 库

```bash
# 设置环境变量
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/25.2.9519653
export VCPKG_ROOT=/opt/vcpkg
export VCPKG_INSTALLED_ROOT=/opt/vcpkg/installed

# 编译 Rust 库 (约 5-10 分钟)
cargo ndk --target aarch64-linux-android --platform 24 build --release --features flutter
```

编译产物:
- `target/aarch64-linux-android/release/liblibrustdesk.so` (约 27MB)

## 8. 复制库文件到 Flutter 项目

```bash
# 创建 jniLibs 目录
mkdir -p /opt/rustdesk/rustdesk/flutter/android/app/src/main/jniLibs/arm64-v8a

# 复制 Rust 库
cp target/aarch64-linux-android/release/liblibrustdesk.so \
   flutter/android/app/src/main/jniLibs/arm64-v8a/librustdesk.so

# 复制 C++ 运行时库 (重要!缺少会导致崩溃)
cp $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so \
   flutter/android/app/src/main/jniLibs/arm64-v8a/
```

**注意**: `libc++_shared.so` 是必需的,缺少它会导致应用启动时崩溃!

## 9. 构建 Flutter APK

### Debug 版本 (用于测试)

```bash
cd /opt/rustdesk/rustdesk/flutter

# 构建 debug APK
flutter build apk --debug --target-platform android-arm64

# 产物位置
ls -lh build/app/outputs/flutter-apk/app-debug.apk
```

### Release 版本 (用于发布)

Release 版本需要配置签名密钥。

#### 创建签名密钥

```bash
keytool -genkey -v -keystore ~/rustdesk-release-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias rustdesk
```

#### 配置 Gradle 签名

编辑 `flutter/android/key.properties`:

```properties
storePassword=你的密码
keyPassword=你的密码
keyAlias=rustdesk
storeFile=/path/to/rustdesk-release-key.jks
```

编辑 `flutter/android/app/build.gradle`,在 `android` 块中添加:

```gradle
signingConfigs {
    release {
        def keystoreProps = new Properties()
        def keystorePropsFile = rootProject.file('key.properties')
        if (keystorePropsFile.exists()) {
            keystoreProps.load(new FileInputStream(keystorePropsFile))
        }
        
        keyAlias keystoreProps['keyAlias']
        keyPassword keystoreProps['keyPassword']
        storeFile keystoreProps['storeFile'] ? file(keystoreProps['storeFile']) : null
        storePassword keystoreProps['storePassword']
    }
}

buildTypes {
    release {
        signingConfig signingConfigs.release
        minifyEnabled true
        shrinkResources true
        proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
    }
}
```

#### 构建 Release APK

```bash
flutter build apk --release --target-platform android-arm64
```

## 10. 安装和测试

### 安装到设备

```bash
# 通过 USB 连接 Android 设备并启用开发者模式
adb devices

# 安装 debug APK
adb install build/app/outputs/flutter-apk/app-debug.apk

# 或安装 release APK
adb install build/app/outputs/flutter-apk/app-release.apk
```

### 查看日志

```bash
# 实时监控日志
adb logcat | grep -E "rustdesk|flutter|AndroidRuntime"

# 清除日志
adb logcat -c
```

## 常见问题

### 1. 应用启动后立即崩溃

**原因**: 缺少 `libc++_shared.so`

**解决**: 确保复制了 C++ 运行时库到 jniLibs 目录:
```bash
cp $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so \
   flutter/android/app/src/main/jniLibs/arm64-v8a/
```

### 2. Gradle 下载失败

**原因**: 网络问题

**解决**: 配置代理
```bash
# 在 gradle.properties 中添加
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=1080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=1080
```

### 3. vcpkg 编译失败

**原因**: 网络问题或缺少依赖

**解决**: 
- 确保网络可以访问 GitHub 和 Google
- 清理缓存重试: `rm -rf /opt/vcpkg/buildtrees /opt/vcpkg/packages`
- 检查 NDK 版本是否匹配 (需要 r25c 或更新)

### 4. Flutter Bridge 生成失败

**原因**: flutter_rust_bridge_codegen 版本不匹配

**解决**: 使用指定版本
```bash
cargo install flutter_rust_bridge_codegen --version 1.80.1 --features uuid --locked
```

### 5. Kotlin 版本不兼容

**原因**: Gradle 插件版本过旧

**解决**: 更新 `flutter/android/build.gradle` 中的 Kotlin 版本:
```gradle
ext.kotlin_version = '1.9.10'
```

## 完整编译脚本

创建一个自动化脚本 `build-android.sh`:

```bash
#!/bin/bash
set -e

# 环境变量
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/25.2.9519653
export VCPKG_ROOT=/opt/vcpkg
export VCPKG_INSTALLED_ROOT=/opt/vcpkg/installed
export PATH=$PATH:/opt/flutter/bin:$VCPKG_ROOT

echo "=== 1. 编译 vcpkg 依赖 ==="
cd /opt/rustdesk/rustdesk/flutter
bash build_android_deps.sh arm64-v8a

echo "=== 2. 生成 Flutter Bridge ==="
flutter pub get
~/.cargo/bin/flutter_rust_bridge_codegen \
  --rust-input ../src/flutter_ffi.rs \
  --dart-output ./lib/generated_bridge.dart \
  --c-output ./android/app/src/main/cpp/bridge_generated.h

echo "=== 3. 编译 Rust 库 ==="
cargo ndk --target aarch64-linux-android --platform 24 build --release --features flutter

echo "=== 4. 复制库文件 ==="
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/liblibrustdesk.so \
   android/app/src/main/jniLibs/arm64-v8a/librustdesk.so
cp $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so \
   android/app/src/main/jniLibs/arm64-v8a/

echo "=== 5. 构建 APK ==="
flutter build apk --debug --target-platform android-arm64

echo "=== 完成 ==="
ls -lh build/app/outputs/flutter-apk/app-debug.apk
```

使用方法:
```bash
chmod +x build-android.sh
./build-android.sh
```

## 编译时间参考

- vcpkg 依赖编译: 10-15 分钟
- Rust 库编译: 5-10 分钟
- Flutter APK 构建: 2-5 分钟
- **总计: 约 20-30 分钟** (首次编译)

后续编译会使用缓存,速度更快。

## 参考链接

- [RustDesk 官方文档](https://rustdesk.com/docs/en/dev/build/)
- [Flutter Android 编译](https://docs.flutter.dev/deployment/android)
- [vcpkg Android 支持](https://github.com/microsoft/vcpkg/blob/master/docs/users/android.md)
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk)

## 更新日志

- 2026-07-23: 初始版本,基于 Ubuntu 24.04 + Flutter 3.24.5 + NDK r25c
