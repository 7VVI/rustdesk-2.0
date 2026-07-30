<p align="center">
  <img src="res/logo-header.svg" alt="RustDesk - rdsdk 被控端可嵌入版"><br>
</p>

# RustDesk 被控端可嵌入版（rdsdk）

> 本仓库是 [RustDesk](https://github.com/rustdesk/rustdesk) 的二次开发分支。
> 核心目标：把 RustDesk 移动端的**单个界面**（被控端 / 控制端）封装成可被任意 Android
> 宿主 App **直接调用的 AAR（rdsdk）**，并针对「嵌入式接入 + 自建服务器 + 固定机号」
> 场景做了一系列增强。
>
> **RustDesk 本体的产品介绍、功能特性与常规使用文档，官方仓库已有，这里不再赘述**：
> 👉 https://github.com/rustdesk/rustdesk

---

## 基线版本

- 基于 RustDesk **v1.4.9**（见 `Cargo.toml` 的 `version = "1.4.9"`）。
- 仅在其之上做增量增强，尽量不改动无关代码。
- hbb_common 采用本组织的 fork（放宽了数字机号 id 的格式校验），主仓库以子模块指针引用。

---

## 本项目相对官方 1.4.9 的升级

### 1. rdsdk —— 界面级 AAR 封装
把 RustDesk 移动端拆成「界面级」库：宿主想用哪个界面就调哪个，而不是启动整个客户端。

| 角色 | 界面 | 对外方法 |
|------|------|----------|
| 被控端 | 共享屏幕（ServerPage）：上线等待被远程控制 | `RDMainRunner.startServerScreen(...)` |
| 控制端 | 连接（ConnectionPage）：输入对方机号发起远程会话 | `RDMainRunner.startConnectScreen(...)` |

- **不复制代码**：通过 Gradle `sourceSets` 复用 `:app` 的原生实现、资源与 `jniLibs`（含 `librustdesk.so`），只新增对外 API 与 Activity。
- **多 Dart 入口**：用 `@pragma('vm:entry-point')` 顶层函数 + `getDartEntrypointFunctionName()` 选择界面，共用同一份 `libapp.so`。

### 2. 运行时注入自建服务器配置（无需重编）
宿主通过 Intent extras 传入自建服务器 `idServer / relayServer / key`，经平台通道
`custom_config` 注入到 Rust 核心，等价于「内置了服务器」。默认开启 UDP 打洞
（`enable-udp-punch`）与 P2P 直连（`direct-server`）。

### 3. 固定 id / 固定密码注入
宿主可下发固定设备号与固定永久密码；被控端自动把校验方式切到「使用永久密码」
（`use-permanent-password`），不再显示/等待随机一次性密码。

### 4. 自定义机号（设备 id）注册 ★
支持宿主动态传入的**机号**真正注册到交会服务器，其它设备即可用该机号连上本机，
不再回落成随机 id。原理与三处关键修复见
[`flutter/android/rdsdk/RDSDK_GUIDE.md` 第 4.5 节](flutter/android/rdsdk/RDSDK_GUIDE.md)：

- 放宽应用条件：自建服务器对 `change_id` 校验回 `server_not_support`（或暂时连不上）时，
  移动端仍本地写入 id，走与自动数字 id 相同的普通注册路径；
- 写入 id 后 `RendezvousMediator::restart()`，让在线 mediator 用新机号重新注册；
- 新增 `KEEP_FIXED_ID`：固定机号模式下收到 `UUID_MISMATCH` 不再生成随机 id 顶替，保住机号；
- 机号格式校验放宽为 6–16 位、支持数字开头（hbb_common fork）。

### 5. 被控端交互精简
- 去掉悬浮窗；
- 录屏授权默认整屏、去掉「整屏 / 单应用」选择（Android 14+）；
- 停止服务按钮状态实时同步（授权后即出现、点停止即消失）；
- 去掉首次进入的「你可能被骗了」诈骗告警弹窗；
- 去掉被控端右上角三个点（⋮）菜单（机号/密码由宿主下发，无需暴露这些设置项）。

### 6. 独立交付与示例
- **rdsdk-dist**：预编译交付包（`rdsdk-release.aar` + 本地 Maven `m2repository`），
  宿主以 `flatDir` + 本地 Maven 方式引用，无需联网拉 RustDesk 相关依赖；
- **demohost**：最小宿主示例 App；
- 预编译包通过 **GitHub Release** 分发（编译产物不入库）。

---

## 快速使用（宿主接入）

```kotlin
import com.carriez.flutter_hbb.RDMainRunner

// 被控端：进入「共享屏幕」，以机号 + 固定密码在自建服务器上线
RDMainRunner.startServerScreen(
    context,
    "your-id-server:21116",   // idServer
    "your-relay-server:21117",// relayServer
    "SERVER_PUBLIC_KEY",      // key（可空）
    "100001",                 // 机号（设备 id；空=随机）
    "123456"                  // 永久密码（可空）
)

// 控制端：进入「连接」，输入对方机号发起远程会话
RDMainRunner.startConnectScreen(context, "your-id-server:21116", "your-relay-server:21117", "SERVER_PUBLIC_KEY")
```

> 完整接入步骤（Gradle 仓库配置、依赖声明、清单合并、参数说明、FAQ）见
> [`flutter/android/rdsdk/RDSDK_GUIDE.md`](flutter/android/rdsdk/RDSDK_GUIDE.md)。

### 获取预编译 AAR
- GitHub Release：**[rdsdk-dist-v1.1](../../releases/tag/rdsdk-dist-v1.1)**
  （`rdsdk-dist-v1.1.zip` 内含 `libs/rdsdk-release.aar` + `m2repository/` + `README.md` + `samples/`）

---

## 关键源码位置

| 路径 | 说明 |
|------|------|
| `flutter/android/rdsdk/` | rdsdk AAR 源码（对外 API `RDMainRunner` + 3 个 Activity） |
| `flutter/android/demohost/` | demo 宿主示例 App |
| `flutter/lib/main.dart` | 新增 `rdServerScreen` / `rdConnectScreen` Dart 入口点 |
| `flutter/lib/mobile/rdsdk_screens.dart` | 单页壳 `_RdApp` + `rdScreenMain` 引导 |
| `flutter/lib/mobile/pages/server_page.dart` | 被控端：`custom_config` 注入、`applyCustomConfigAndStart` |
| `src/ui_interface.rs` | 机号本地应用条件放宽 + 应用后重启 mediator |
| `src/rendezvous_mediator.rs` | `KEEP_FIXED_ID`：固定机号模式下不因 `UUID_MISMATCH` 换随机 id |
| `flutter/android/rdsdk/RDSDK_GUIDE.md` | 封装原理 / 编译 / 接入 / 自定义机号原理 完整指南 |

---

## 编译

rdsdk / demohost 的完整编译流程（Dart → `libapp.so` → `flutter_release` → `rdsdk` → `demohost`，
以及三架构 `librustdesk.so` 的 Rust 编译）见
[`RDSDK_GUIDE.md` 第 5 节](flutter/android/rdsdk/RDSDK_GUIDE.md)。

RustDesk 桌面/常规构建请参考官方文档：https://rustdesk.com/docs/en/dev/build/

---

## 许可证与声明

- 本项目基于 RustDesk，遵循其原始开源许可证（AGPL-3.0），版权归各自作者所有。
- **免责声明**：开发者不认可、不支持任何不道德或非法使用本软件的行为（如未经授权的访问、
  控制或侵犯隐私）。作者不对任何滥用行为负责。
