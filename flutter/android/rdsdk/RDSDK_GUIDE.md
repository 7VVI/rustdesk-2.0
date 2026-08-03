# rdsdk —— RustDesk 界面级 AAR 封装指南

> 把 RustDesk 移动端的**单个界面**封装成可被任意宿主 App 直接调用的 Android 库（AAR）：
> 宿主想用哪个界面就调哪个，而不是启动整个 RustDesk 客户端。
>
> - **被控端**：只进入"共享屏幕（ServerPage）"，上线等待被远程控制。
> - **控制端**：只进入"连接（ConnectionPage）"，输入对方机号发起远程会话。

本文覆盖：AAR 是如何从整包抽取的 → 目录结构 → 关键实现原理 → 如何编译 AAR → 如何使用 AAR → 常见问题。

---

## 1. 背景与设计目标

RustDesk 移动端原本是一个完整 Flutter 客户端：`HomePage` 带底部 Tab，聚合了
`ConnectionPage`（连接/控制端）、`ChatPage`、`ServerPage`（共享屏幕/被控端）、`SettingsPage`。

宿主 App 通常只需要其中**一个界面**。因此把 RustDesk 拆成"界面级"AAR：

| 角色 | 界面 | Dart 入口 | 原生 Activity | 对外方法 |
|------|------|-----------|---------------|----------|
| 被控端 | ServerPage（共享屏幕） | `rdServerScreen` | `RDServerActivity` | `RDMainRunner.startServerScreen(...)` |
| 控制端 | ConnectionPage（连接） | `rdConnectScreen` | `RDConnectActivity` | `RDMainRunner.startConnectScreen(...)` |

设计原则：
- **不复制代码**：rdsdk 通过 Gradle `sourceSets` 复用 `:app` 的原生实现、资源、`jniLibs`（含 `librustdesk.so`），只新增自己的对外 API 与 Activity。
- **多 Dart 入口**：用 `@pragma('vm:entry-point')` 顶层函数 + `FlutterActivity.getDartEntrypointFunctionName()` 选择界面，共用同一份 `libapp.so`。
- **运行时注入服务器配置**：宿主通过 Intent extras 传入自建服务器（idServer/relay/key）与设备号，`custom_config` 平台通道注入到 Rust 核心，无需重编。
- **无注册/鉴权层**：SDK 只负责拉起界面与传参。

---

## 2. AAR 是如何从整包抽取的

抽取思路是"**加壳 + 复用**"，不是"裁剪"。共三层：

### 2.1 原生层（Kotlin）——新增薄壳，复用 :app 实现
`rdsdk` 模块自身只写 4 个 Kotlin 文件（对外 API + 界面 Activity），
其余被控端能力（`MainService`、`InputService`、`FloatingWindowService`、`ffi` 等）
**不复制**，而是在 `rdsdk/build.gradle` 里通过 `sourceSets` 直接把 `:app` 的目录挂进来编译：

```groovy
sourceSets {
    main.java.srcDirs += 'src/main/kotlin'          // rdsdk 自己的 API/Activity
    main.java.srcDirs += '../app/src/main/kotlin'   // 复用 :app 被控端原生实现
    main.res.srcDirs += '../app/src/main/res'       // 复用资源（含 LaunchTheme 等）
    main.jniLibs.srcDirs += '../app/src/main/jniLibs' // 复用 librustdesk.so（3 架构）
    main.proto.srcDirs += '../../../libs/hbb_common/protos'
    main.proto.includes += "message.proto"
}
```

### 2.2 Dart/Flutter 层——多入口 + 单页壳
- 在 `lib/main.dart`（**根库**）新增两个 `@pragma('vm:entry-point')` 入口函数
  `rdServerScreen` / `rdConnectScreen`，各自委托到 `rdScreenMain(isServer:)`。
- `lib/mobile/rdsdk_screens.dart` 提供 `rdScreenMain` 与单页壳 `_RdApp`：
  复刻 `main.dart` 中 `App` 的 `MultiProvider + GetMaterialApp`，但 `home` 换成
  只承载一个页面的 `Scaffold`，因此没有底部 Tab。
- Dart 代码经 `flutter build aar` / `:app:assembleRelease` 编译进 `libapp.so`，
  再随 `flutter_release` AAR 交付。

### 2.3 交付层——rdsdk-dist 独立本地 Maven 包
把编译产物整理成一个**离线目录** `rdsdk-dist/`，宿主直接以本地 Maven + flatDir 方式引用，
无需联网拉 RustDesk 相关依赖（见第 6 节）。

---

## 3. 目录与文件结构

### 3.1 rdsdk 模块（AAR 源码）
路径：`flutter/android/rdsdk/`

```
rdsdk/
├── build.gradle                 # AAR 构建配置（复用 :app 源码/资源/jniLibs）
├── consumer-rules.pro           # 传递给宿主的 ProGuard keep 规则
└── src/main/
    ├── AndroidManifest.xml      # 注册 3 个 Activity + 服务 + 权限
    └── kotlin/com/carriez/flutter_hbb/
        ├── RDMainRunner.kt      # 对外 API 入口（唯一需要宿主调用的类）
        ├── RDMainActivity.kt    # FlutterActivity 基类：引擎/通道/custom_config 注入
        ├── RDServerActivity.kt  # 被控端 Activity，入口点 rdServerScreen
        └── RDConnectActivity.kt # 控制端 Activity，入口点 rdConnectScreen
```

### 3.2 Dart/Flutter 侧改动
路径：`flutter/lib/`

```
lib/
├── main.dart                    # 新增 rdServerScreen / rdConnectScreen 入口点（必须在根库）
└── mobile/
    ├── rdsdk_screens.dart       # rdScreenMain 引导 + 单页壳 _RdApp
    └── pages/
        ├── server_page.dart     # 被控端 ServerPage + androidChannelInit / androidConnectChannelInit
        └── connection_page.dart # 控制端 ConnectionPage
```

### 3.3 交付包 rdsdk-dist（编译产物，供宿主使用）
路径：`/opt/rustdesk/rdsdk-dist/`

```
rdsdk-dist/
├── libs/
│   └── rdsdk-release.aar        # rdsdk 本体（~38MB，含被控端原生代码 + librustdesk.so 3 架构）
├── m2repository/                # 本地 Maven 仓库：Flutter 引擎/Dart + 各插件 AAR
│   └── com/carriez/rdflutter/flutter_release/1.0/
│       └── flutter_release-1.0.aar   # Flutter 引擎 + libapp.so（Dart AOT，~16MB）
├── README.md                    # 接入说明
└── samples/DemoActivity.kt      # 最小接入示例
```

### 3.4 模块依赖关系
```
宿主 App (demohost)
   │  implementation(name:'rdsdk-release', ext:'aar')          ← libs/
   │  implementation "com.carriez.rdflutter:flutter_release:1.0"← m2repository/
   ▼
rdsdk-release.aar ──依赖──▶ flutter_release-1.0.aar（内含 libapp.so = 编译后的 Dart）
   │                                    │
   │  复用（编译期 sourceSets）           │  含 rdServerScreen/rdConnectScreen 入口
   ▼                                    ▼
:app 原生被控端实现 + librustdesk.so   Rust 核心 + Flutter 引擎
```

---

## 4. 关键实现原理（务必理解，否则易踩黑屏坑）

### 4.1 多 Dart 入口点必须定义在根库 main.dart（黑屏根因）
Flutter add-to-app 中，`FlutterActivity` 默认在**根库（`lib/main.dart`）** 内按函数名查找入口点。
若把 `rdServerScreen` / `rdConnectScreen` 定义在别的文件（如 `rdsdk_screens.dart`），即使被
import 且已编译进 `libapp.so`，运行时仍会报：

```
Could not resolve main entrypoint function.
Could not create root isolate.
```
→ isolate 无法启动 → **界面黑屏**。

✅ 正确做法：入口函数放 `main.dart`，实现委托给其他库的公开函数：
```dart
// lib/main.dart（根库）
@pragma('vm:entry-point')
void rdServerScreen() => rdScreenMain(isServer: true);

@pragma('vm:entry-point')
void rdConnectScreen() => rdScreenMain(isServer: false);
```
（覆写 `getDartEntrypointLibraryUri()` 指向其他库 URI 的方式，在本项目 AOT release 下实测不生效。）

### 4.2 Activity 如何选择界面
```kotlin
class RDServerActivity : RDMainActivity() {
    override fun getDartEntrypointFunctionName() = "rdServerScreen"
}
class RDConnectActivity : RDMainActivity() {
    override fun getDartEntrypointFunctionName() = "rdConnectScreen"
}
```

### 4.3 运行时服务器配置注入（custom_config 通道）
`RDMainActivity.onResume()` 把 Intent extras 组装成 map，通过平台通道
`invokeMethod("custom_config", map)` 发给 Dart：
- **被控端**（`androidChannelInit`）：`custom_config` → `applyCustomConfigAndStart`，
  设置服务器配置**并启动**被控服务、按需申请录屏权限。
- **控制端**（`androidConnectChannelInit`）：`custom_config` → `applyCustomServerConfigOnly`，
  **只注入服务器配置，不启动被控、不申请录屏**（控制端不需要）。

extras / map 的键：`idServer`、`relayServer`、`key`、`id`、`password`。

### 4.4 控制端连接后的会话
控制端在同一个 Flutter Navigator 内 `connect()` → `push(RemotePage)`，
不需要额外原生 Activity；会话链路由 Rust 核心 + session FFI 提供。

### 4.5 自定义机号（设备 id）注册原理

宿主传入的 `id`（机号）要真正**注册到交会服务器**、并让其它设备用这个机号连上你，
涉及一条贯穿 原生 → Dart → Rust 核心 → 交会服务器 的链路。理解它才能明白为什么
“传了 id 却还是注册成原来的随机 id”，以及现在是如何修好的。

#### 4.5.1 机号是怎么一路传到 Rust 核心的
```
RDMainRunner.startServerScreen(ctx, …, id="100001", …)
   │  Intent extra "id"
   ▼
RDMainActivity.onResume() ──invokeMethod("custom_config", {id:"100001", …})──▶ Dart
   ▼
server_page.dart: applyCustomConfigAndStart(cfg)
   1) 写服务器配置(idServer/relay/key)
   2) 写固定密码 + 校验方式
   3) startService()            → 被控服务/交会 mediator 启动
   4) mainChangeId("100001")     → 落 id + 触发重新注册   ★关键
   5) fetchID()                 → 刷新界面回显
   ▼
Rust: main_change_id → change_id_shared_("100001")
```

#### 4.5.2 关键认知：机号注册走的是“普通注册”，不是 change_id 校验
RustDesk 设备上线时，交会 mediator 每次(重)连都会用 `Config::get_id()` 里的当前 id 做
`register_pk` 向服务器注册——**自动生成的数字 id 也是走这条路注册的**。也就是说：

> **只要把机号写进 `Config` 的 id，再让 mediator 用新 id 重连一次，服务器就会以该机号注册。**

`change_id` 里额外的那次 `register_pk`（带 old_id 的“改名”RPC）只是一个**可用性校验**，
很多自建服务器并不支持、会回 `server_not_support`；它失败**不代表**机号不能用。

#### 4.5.3 曾经“机号不生效”的三个根因与对应修复
（均在主 crate `src/ui_interface.rs` 与 `src/rendezvous_mediator.rs`）

1. **校验失败就不落 id**：自建服务器回 `server_not_support`（或暂时连不上）时，旧逻辑
   直接放弃，不写 id → 仍是随机 id。
   ✅ 修复：`change_id_shared_` 放宽应用条件——移动端**除“格式非法 / 已被他人占用
   (Not available)”外都本地写入 id**（`Config::set_id`），随后走 4.5.2 的普通注册。

2. **改了 id 但 mediator 还用旧 id 在线**：mediator 只在(重)连时读一次 `Config::get_id()`，
   startService 已先用旧/随机 id 注册过了。
   ✅ 修复：写入 id 后调用 `RendezvousMediator::restart()`，强制断开重连，用新机号重新 `register_pk`。

3. **服务器回 UUID_MISMATCH 时核心自动换随机 id**：当某机号在服务器上已绑定到**另一把
   设备密钥/uuid** 时，服务器回 `UUID_MISMATCH`，核心默认调用 `Config::update_id()`
   生成一个 `1xxxxxxxxx` 的随机 id 顶替——这正是“注册的还是原来的随机 id”的直接来源。
   ✅ 修复：新增全局标志 `KEEP_FIXED_ID`，固定机号模式下收到 UUID_MISMATCH **不再重生成**，
   保住机号（真正的冲突需在服务器侧清掉旧绑定）。

#### 4.5.4 uuid 是什么、为什么会 UUID_MISMATCH
服务器用 `id → (uuid, 公钥)` 绑定设备。Android 上 `get_uuid()` 最终取 `key_pair`（设备公钥），
**它随 App 全新安装重新生成**。因此：
- **全新未注册过的机号** → 服务器无记录 → `register_pk` 直接成功，机号即刻上线（首次注册的正常路径）。
- **同一机号曾用别的设备/别的安装(不同密钥)注册过** → `UUID_MISMATCH`。此时 `KEEP_FIXED_ID`
  会保住机号不被换掉，但要真正以该机号上线，需在服务器端清除旧绑定（或换一个未用过的机号）。

#### 4.5.5 使用建议
- 给每台设备分配**唯一且未注册过**的机号，首次即可稳定以机号上线。
- 机号需满足格式校验（6–16 位，支持数字开头）；不合法(`INVALID_FORMAT`)不会被应用。
- 判断是否生效：界面 ID 回显 = 机号，且控制端能用该机号连上；`logcat` 可见
  `rdsdk change id -> '<机号>'` 与（冲突时）`UUID_MISMATCH ... keeping fixed id`。

---

## 5. 如何编译 AAR

### 5.1 环境要求
| 组件 | 版本/路径 |
|------|-----------|
| Flutter SDK | 3.24.5，`/opt/flutter`（`local.properties` 的 `flutter.sdk` 指向它） |
| Android SDK | `/opt/android-sdk`（`sdk.dir`）；含 API 33/34、build-tools 34 |
| Android NDK | r25c（25.2.9519653） |
| AGP / Gradle | AGP 7.3.1 / Gradle 7.6.x |
| Kotlin 插件 | 2.1.21（`:app` 强制 `kotlin-stdlib` 1.9.10） |
| Rust 产物 | `librustdesk.so`（arm64-v8a/armeabi-v7a/x86_64），复用 `flutter/android/app/src/main/jniLibs`，本次无需重编 Rust |

> 构建目录被重定向：根 `build.gradle` 有 `rootProject.buildDir = '../build'`，
> 所有产物在 `flutter/build/` 下（而非各模块的 `build/`）。

统一设置环境变量：
```bash
export PATH=/opt/flutter/bin:$PATH
export ANDROID_HOME=/opt/android-sdk
```

### 5.2 第一步：编译 Dart（生成含入口点的 libapp.so）
改动 Dart 后必须重编。因项目 `settings.gradle` 结构导致 `flutter build aar` 直接跑会失败，
本项目用 `:app:assembleRelease` 触发 Dart 编译（Dart 阶段在 Kotlin/打包之前）：

```bash
cd /opt/rustdesk/rustdesk/flutter
flutter clean && flutter pub get         # Dart 改动后务必 clean，否则用旧 AOT 缓存

cd /opt/rustdesk/rustdesk/flutter/android
./gradlew :app:assembleRelease -x packageRelease   # -x packageRelease 跳过签名
```

验证入口点确实进了 `libapp.so`（应能看到 3 个名字）：
```bash
strings /opt/rustdesk/rustdesk/flutter/build/app/intermediates/stripped_native_libs/release/out/lib/arm64-v8a/libapp.so \
  | grep -E "rdServerScreen|rdConnectScreen|rdScreenMain"
```
> 若为空，说明 Dart 未重编或入口点不可达（检查是否在 main.dart、是否 clean）。

### 5.3 第二步：把新 libapp.so 打进 flutter_release AAR
```bash
cd /tmp && rm -rf fla && mkdir fla && cd fla
unzip -q /opt/rustdesk/rdsdk-dist/m2repository/com/carriez/rdflutter/flutter_release/1.0/flutter_release-1.0.aar
SRC=/opt/rustdesk/rustdesk/flutter/build/app/intermediates/stripped_native_libs/release/out/lib
cp "$SRC/arm64-v8a/libapp.so"   jni/arm64-v8a/libapp.so
cp "$SRC/armeabi-v7a/libapp.so" jni/armeabi-v7a/libapp.so
cp "$SRC/x86_64/libapp.so"      jni/x86_64/libapp.so
rm -f flutter_release-1.0.aar && zip -q -r flutter_release-1.0.aar .

# 回写到 dist 与本地 repo（:rdsdk 依赖后者）
cp flutter_release-1.0.aar /opt/rustdesk/rdsdk-dist/m2repository/com/carriez/rdflutter/flutter_release/1.0/
cp flutter_release-1.0.aar /opt/rustdesk/rustdesk/flutter/build/host/outputs/repo/com/carriez/rdflutter/flutter_release/1.0/
```
> `flutter clean` 会删掉 `flutter/build/host/outputs/repo`，若不存在先用
> `mkdir -p` 后从 `rdsdk-dist/m2repository` 拷贝一份种子。

### 5.4 第三步：编译 rdsdk AAR
```bash
cd /opt/rustdesk/rustdesk/flutter/android
./gradlew :rdsdk:assembleRelease
# 产物：flutter/build/rdsdk/outputs/aar/rdsdk-release.aar
cp /opt/rustdesk/rustdesk/flutter/build/rdsdk/outputs/aar/rdsdk-release.aar \
   /opt/rustdesk/rdsdk-dist/libs/rdsdk-release.aar
```
> `e: ... incompatible version of Kotlin ... metadata is 2.1.0, expected 1.6.0` 属 lint 告警，
> 不影响 `BUILD SUCCESSFUL`。

### 5.5 第四步（可选）：编译 demo 宿主验证
```bash
cd /opt/rustdesk/rustdesk/flutter/android
./gradlew :demohost:assembleRelease
# 产物：flutter/build/demohost/outputs/apk/release/demohost-release.apk
```
验证合并清单含三个 Activity、APK 含入口点：
```bash
APK=/opt/rustdesk/rustdesk/flutter/build/demohost/outputs/apk/release/demohost-release.apk
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump xmltree --file AndroidManifest.xml "$APK" | grep -E "RDServer|RDConnect|RDMain"
unzip -p "$APK" lib/arm64-v8a/libapp.so | strings | grep -E "rdServerScreen|rdConnectScreen"
```

### 5.6 编译速查（顺序不能乱）
```
改 Dart  → 5.2 重编 libapp.so → 5.3 回写 flutter_release → 5.4 编 rdsdk → 5.5 验证
只改 Kotlin(rdsdk) → 直接 5.4 编 rdsdk → 5.5 验证（libapp.so 无需重编）
```

---

## 6. 如何使用 AAR（宿主接入）

### 6.1 拷贝交付包
把 `rdsdk-dist/` 放到宿主工程可访问的位置（示例用绝对路径 `/opt/rustdesk/rdsdk-dist`，
实际可放进宿主工程内并改成相对路径）。

### 6.2 宿主 `build.gradle`（app 模块）
```groovy
def rdsdkDist = new File("/opt/rustdesk/rdsdk-dist")
def rdsdkRepo = new File(rdsdkDist, "m2repository")
def rdsdkLibs = new File(rdsdkDist, "libs")

repositories {
    google(); mavenCentral()
    maven { url = "https://jitpack.io" }                                // XXPermissions
    maven { url = "https://storage.googleapis.com/download.flutter.io" } // Flutter 引擎 .so（如已在 m2repository 可省）
    maven { url = rdsdkRepo.toURI().toString() }                        // flutter_release + 插件
    maven {                                                              // rustls（仅 artifact 元数据）
        url = rdsdkRepo.toURI().toString()
        metadataSources.artifact()
        content { includeGroup "rustls" }
    }
    flatDir { dirs rdsdkLibs.path }                                     // rdsdk-release.aar
}

dependencies {
    implementation(name: 'rdsdk-release', ext: 'aar')
    implementation "com.carriez.rdflutter:flutter_release:1.0"
    // rdsdk 的运行时依赖（bare aar 无 POM，需显式声明）：
    implementation "com.google.protobuf:protobuf-javalite:3.20.1"
    implementation "androidx.media:media:1.6.0"
    implementation "com.github.getActivity:XXPermissions:18.5"
    implementation "com.caverock:androidsvg-aar:1.4"
    implementation "rustls:rustls-platform-verifier:0.1.1"
    implementation "androidx.appcompat:appcompat:1.6.1"
}
```
> `minSdk >= 22`；`RDServerActivity`/`RDConnectActivity`/`RDMainActivity` 由 rdsdk 清单
> **自动合并**进宿主，无需在宿主 manifest 手动声明；权限（录屏、前台服务、无障碍等）也随之合并。

### 6.3 调用 API（`RDMainRunner`）
```kotlin
import com.carriez.flutter_hbb.RDMainRunner

// —— 被控端：进入"共享屏幕"，上线等待被控 ——
RDMainRunner.startServerScreen(context, /*id*/"", /*password*/"")                 // 官方服务器
RDMainRunner.startServerScreen(context, idServer, relayServer, key, id, password) // 自建服务器

// —— 控制端：进入"连接"，输入对方机号发起连接 ——
RDMainRunner.startConnectScreen(context)                                          // 官方服务器
RDMainRunner.startConnectScreen(context, idServer, relayServer, key)              // 自建服务器
```

参数说明：
| 参数 | 含义 | 备注 |
|------|------|------|
| `idServer` | ID/交会服务器 `host:port` | 空=官方服务器 |
| `relayServer` | 中继服务器 `host:port` | 可空 |
| `key` | 服务器公钥 | 可空 |
| `id` | 固定设备号（被控端） | 空=随机生成；传入则注册为该机号，原理见 4.5 |
| `password` | 固定密码（被控端） | 空=随机/不设 |
| `inputMode` | 输入注入模式 | `"inapp"`(默认，免无障碍) / `"accessibility"`(跨 App，需无障碍授权)；详见 6.5 |

自建服务器示例：
```kotlin
RDMainRunner.startServerScreen(
    context,
    "control.zhdgps.com:64981",  // idServer
    "control.zhdgps.com:64982",  // relayServer
    "yvs2RoiYpIFdB8rrLe5GPaN3uJH6sJbiS9wbLdIevm4=", // key
    "10001",                     // 设备号
    ""                           // password
)
```

> 兼容别名：旧的 `RDMainRunner.start(...)` 仍可用，但已标 `@Deprecated`，等价于
> `startServerScreen(...)`；新代码请直接用 `startServerScreen` / `startConnectScreen`。

### 6.4 典型宿主流程（参考 demohost）
- 设备列表点某设备 → `startServerScreen(context, idServer, relay, key, 机号, "")` → 直接进共享屏幕、授权录屏后可被远程控制。
- 点"远程控制" → `startConnectScreen(context[, idServer, relay, key])` → 进连接页 → 输入对端机号 → push 进远程会话。
- 服务器配置可由宿主自己的“设置页”保存到 `SharedPreferences`，启动界面时读出来传入。

### 6.5 输入控制模式（inputMode）

被控端接收到的远端触摸/鼠标/键盘事件需要注入到本机。SDK 提供两种注入模式，由
`startServerScreen(..., inputMode=...)` 选择：

| 模式 | 值 | 是否需要无障碍权限 | 控制范围 | 适用场景 |
|------|----|------------------|----------|----------|
| App 内注入（默认） | `"inapp"` | 否 | 仅宿主 App 自己的窗口内 | Kiosk/独占前台 App；不需要跨 App 控制 |
| 无障碍注入 | `"accessibility"` | 是（用户手动授权系统无障碍） | 跨 App / 全系统 | 需要控制 App 之外的界面 |

#### 原理
- **inapp**：远端事件经 Rust 核心 → `MainService.rustPointerInput` →
  `RdInAppInputService` 直接 `dispatchTouchEvent` / `dispatchKeyEvent` 到宿主
  Activity 的 `DecorView`，完全不需要系统无障碍权限。落在自己窗口外的事件静默丢弃。
- **accessibility**：走原有 `InputService`（`AccessibilityService`），
  `dispatchGesture` / `performAction`，可跨 App，但需用户在系统设置里授权无障碍。

#### 选择
```kotlin
// 默认：App 内控制，不弹无障碍授权
RDMainRunner.startServerScreen(context, idServer, relay, key, id, password)
// 等价显式写法
RDMainRunner.startServerScreen(context, idServer, relay, key, id, password, inputMode = "inapp")

// 跨 App 控制（需用户授权无障碍）
RDMainRunner.startServerScreen(context, idServer, relay, key, id, password, inputMode = "accessibility")
```

#### inapp 模式的限制
- 仅控制宿主 App 自身窗口内的 UI；窗口外的点击静默丢弃。
- 不支持系统级手势（Home/Back/Recents 全局键、通知栏下拉）——这些可由宿主 App 自己处理。
- 鼠标滚轮/右键等高级手势暂不支持（静默丢弃），后续可扩展。

---

## 7. 常见问题（FAQ）

| 现象 | 原因 | 处理 |
|------|------|------|
| 进界面黑屏，logcat 有 `Could not resolve main entrypoint function` | 入口点未在根库 `main.dart` | 见 4.1，把入口函数放 main.dart 并重编 |
| 改了 Dart 但行为没变 | 用了旧 AOT 缓存 | 先 `flutter clean` 再 5.2 重编，`strings libapp.so` 校验入口点 |
| `找不到 rdsdk-release.aar / flutter_release` | dist 路径错或 `flutter clean` 删了 repo | 校验 6.2 路径；`build/host/outputs/repo` 从 `rdsdk-dist/m2repository` 补种 |
| 构建日志一堆 `incompatible version of Kotlin ... metadata` | lint 告警 | 无害，只要 `BUILD SUCCESSFUL` |
| 找不到 APK/AAR 产物 | 构建目录被 `rootProject.buildDir='../build'` 重定向 | 去 `flutter/build/<module>/outputs/...` 找 |
| 控制端要不要录屏权限 | 不需要 | 控制端走 `androidConnectChannelInit`，不启被控、不申请录屏 |
| 传了机号却注册成随机 id（`1xxxxxxxxx`） | 该机号已在服务器绑定到另一把密钥/uuid → UUID_MISMATCH | 见 4.5.3/4.5.4：换一个未注册过的机号，或在服务器侧清掉旧绑定 |
| 机号未生效、仍显示旧 id | 未重编含修复的 `librustdesk.so`，或机号格式非法 | 重编三架构 Rust（见 4.5.3）；机号需 6–16 位 |

---

## 8. 附录：对外 API 速查

```kotlin
object RDMainRunner {
    // 被控端（共享屏幕 / ServerPage）
    fun startServerScreen(context, id="", password="")
    fun startServerScreen(context, idServer, relayServer, key, id="", password="", inputMode="inapp")

    // 控制端（连接 / ConnectionPage）
    fun startConnectScreen(context)
    fun startConnectScreen(context, idServer, relayServer, key)

    // 兼容别名（@Deprecated → startServerScreen）
    fun start(context, id="", password="")
    fun start(context, idServer, relayServer, key, id="", password="")
    fun getIntent(context, idServer="", relayServer="", key="", id="", password="", inputMode="inapp"): Intent
}
```

Maven 坐标：
- rdsdk 本体：`flatDir` → `rdsdk-release`（`ext:'aar'`）
- Flutter 引擎+Dart：`com.carriez.rdflutter:flutter_release:1.0`
