# 输入控制实现文档（inapp 模式 / accessibility 模式）

> rdsdk 被控端的输入注入有两种模式，由 `startServerScreen(..., inputMode=...)` 选择：
> - **`inapp`（默认）**：免无障碍权限，控制**整个宿主 App**（所有页面 + 弹窗）
> - **`accessibility`**：需无障碍授权，可**跨 App / 全系统**控制

---

## 1. 整体架构

```
控制端(PC/手机)
  │ sessionSendMouse / sessionSendKey (protobuf MouseEvent / KeyEvent)
  ▼
Rust 核心 (connection.rs)
  │ call_main_service_pointer_input("mouse", mask, x, y)
  │ call_main_service_key_event(data)
  ▼ (JNI)
MainService.rustPointerInput / rustKeyEventInput  ← :app 模块, 单一实例
  │
  ├── inputMode == 0 (accessibility) → InputService (AccessibilityService)
  │       dispatchGesture() / performAction()  —— 需无障碍权限, 可跨App
  │
  └── inputMode == 1 (inapp) → RdInAppInputService
          dispatchTouchEvent() / commitText()   —— 免权限, 仅宿主App内
```

### 关键设计：inputMode 存储位置

`inputMode` 存在 `MainService.companion`（`:app` 模块，只编译一次）。
**不能**放在 `common.kt`——该文件被 `:rdsdk` 的 `sourceSets += '../app/src/main/kotlin'` 复用，会导致同一静态变量被编译两份，运行时读写的是不同副本（曾导致 inapp 模式不生效的 bug）。

---

## 2. inapp 模式实现原理

### 2.1 触摸/鼠标事件注入

**核心方法**：向宿主 App 当前前台 Activity 的 `DecorView` 调用 `dispatchTouchEvent(MotionEvent)`。

Flutter/Android 原生的 View 树正常接收触摸事件的入口就是 `DecorView.dispatchTouchEvent`，所以构造合法的 `MotionEvent` dispatch 进去，等价于真实手指触摸。

**文件**：`RdInAppInputService.kt`

```
远端 MouseEvent(mask, x, y)
  → MainService.rustPointerInput(kind=1, mask, x, y)
  → RdInAppInputService.onMouseInput(mask, x, y)
      1. 跟踪鼠标位置: x/y 非零时更新 mouseX/mouseY (×SCREEN_INFO.scale)
      2. mask=LEFT_DOWN  → dispatchPointer(ACTION_DOWN, mouseX, mouseY)
         mask=LEFT_UP    → dispatchPointer(ACTION_UP,   mouseX, mouseY)
         mask=LEFT_MOVE   → dispatchPointer(ACTION_MOVE, mouseX, mouseY) [拖拽中]
         mask=0           → 仅更新位置(不dispatch)
  → dispatchPointer(action, x, y)
      1. 找目标 DecorView(见下文"弹窗支持")
      2. 构造 MotionEvent(downTime, action, x, y, source=TOUCHSCREEN)
      3. 屏幕坐标 → 本地坐标 (getLocationOnScreen + offsetLocation)
      4. mainHandler.post { dv.dispatchTouchEvent(me) }
```

#### 鼠标位置跟踪（关键，曾踩坑）

PC 控制端 `sessionSendMouse({type:'down', buttons:'left'})` **不带 x/y 坐标**，protobuf MouseEvent 的 `x=0, y=0`。被控端必须像原始 `InputService` 一样：
- 用 `mask=0`（纯移动）事件持续跟踪 `mouseX`/`mouseY`
- DOWN/UP 时用**跟踪到的位置**（而非事件携带的 0,0）

| mask 值 | 含义 | MouseEvent 组成 | 处理 |
|---------|------|----------------|------|
| `0` | 纯移动 | x/y=光标位置 | 更新 mouseX/mouseY，不 dispatch |
| `8` (LEFT_MOVE) | 左键拖拽移动 | x/y=光标位置 | 更新 + dispatch MOVE |
| `9` (LEFT_DOWN) | 左键按下 | **x/y=0** | dispatch DOWN at mouseX/mouseY |
| `10` (LEFT_UP) | 左键抬起 | **x/y=0** | dispatch UP at mouseX/mouseY |

mask 组成：`type(低3位) | (button << 3)`
- `MOUSE_TYPE_DOWN=1`, `MOUSE_TYPE_UP=2`, `MOUSE_TYPE_MOVE=0`
- `MOUSE_BUTTON_LEFT=1`
- 左键按下：`1 | (1<<3) = 9`

#### 坐标缩放

`SCREEN_INFO.scale` 通常为 1（真实像素=逻辑像素），乘以它保证与原始 InputService 一致。

### 2.2 控制整个宿主 App（多 Activity）

**文件**：`RdForegroundActivityTracker.kt`

问题：`dispatchTouchEvent` 只能到达指定 View 树。宿主 App 有多个 Activity（设备列表、设置、业务页），每个 Activity 有独立的 DecorView。

解决：用 `Application.registerActivityLifecycleCallbacks` 跟踪**当前前台 Activity**：

```kotlin
app.registerActivityLifecycleCallbacks(object : ... {
    override fun onActivityResumed(a: Activity) { currentActivity = a }
    override fun onActivityPaused(a: Activity) {
        if (currentActivity === a) currentActivity = null
    }
})
```

`dispatchPointer` 优先用 `RdForegroundActivityTracker.currentActivity`，所以无论用户在宿主 App 的哪个页面，远端点击都能到达。

> 注册时机：`RDMainActivity.onCreate`（inapp 模式），幂等（只注册一次）。

### 2.3 弹窗（Dialog/BottomSheet）支持

**问题**：AlertDialog 创建独立 Window，其 DecorView 与 Activity 主 Window 的 DecorView **互不相通**——dispatch 到主 DecorView 的事件到不了弹窗。

**解决**：
1. `RdForegroundActivityTracker` 维护一个 `dialogDecorViews` 列表（`registerDialogWindow`/`unregisterDialogWindow`）
2. `dispatchPointer` 遍历「主 window + 所有 dialog window」，按坐标 hitTest 命中最上层的那个：
   ```kotlin
   val candidates = mutableListOf<View>()
   act.window?.decorView?.let { candidates.add(it) }          // 主 window (底)
   candidates.addAll(RdForegroundActivityTracker.snapshotDialogDecorViews())  // dialogs (上)
   val dv = candidates.reversed()                             // 从上往下找
       .firstOrNull { it.isShown && hitTestView(it, x, y) }
   ```
3. hitTest：`view.getLocationOnScreen()` + 比较坐标是否在 bounds 内

#### 屏幕坐标 → 本地坐标转换（关键，曾踩坑）

弹窗是悬浮窗口，其 DecorView 在屏幕上有偏移（不在 (0,0)）。`dispatchTouchEvent` 期望的是**相对于 DecorView 自身左上角的本地坐标**。如果直接把屏幕坐标 dispatch 给弹窗 DecorView：
- 触摸点落在弹窗"外面"
- AlertDialog 当成"点击外部"自动关闭
- 按钮/EditText 永远收不到事件

**修复**：dispatch 前将屏幕坐标转换为本地坐标：
```kotlin
val loc = IntArray(2)
dv.getLocationOnScreen(loc)
me.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
```

对于主 Activity 的 DecorView（全屏，位于 (0,0)），偏移量为 0，无影响。

**宿主 App 需注册弹窗**（因为普通 App 无法枚举其他 Window）：

```kotlin
val dialog = AlertDialog.Builder(this).setTitle(...).create()
dialog.setOnShowListener {
    dialog.window?.decorView?.let {
        RdForegroundActivityTracker.registerDialogWindow(it)
    }
}
dialog.setOnDismissListener {
    dialog.window?.decorView?.let {
        RdForegroundActivityTracker.unregisterDialogWindow(it)
    }
}
dialog.show()
```

#### 为什么宿主 App 必须手动注册弹窗？

Android 中，每个 Dialog/BottomSheetDialog 会创建一个**独立的 Window**（`PhoneWindow`），它有自己的 DecorView。这个 Window 与 Activity 的主 Window 是**两个平级的顶层 Window**，不存在父子关系：

```
WindowManager (进程级)
  ├── Activity 主 Window  → decorView A (全屏, 位于 0,0)
  └── Dialog Window       → decorView B (悬浮, 有偏移)
```

`dispatchTouchEvent(decorView, event)` 只能将事件分发到**指定 DecorView 的 View 树**。decorView A 和 decorView B 是两棵独立的树，事件不会互通。

SDK 无法自动发现弹窗的 DecorView，因为 Android **没有公开 API 让普通 App 枚举自己进程内的所有 Window**：

| 尝试方案 | 为什么不行 |
|---------|-----------|
| `activity.window.decorView` | 只能拿到主 Window，拿不到 Dialog 的 Window |
| 在主 DecorView 上加 `ViewTreeObserver` | Dialog 的 DecorView 不是主 DecorView 的子 View，布局变化监听不到它 |
| `WindowManager` 枚举所有 Window | Android 没有这个公开 API。`WindowManager` 只能 `addView`/`removeView`，不能 `getViews()` |
| 反射 `WindowManagerImpl.mViews` | 不同 Android 版本字段名/结构不同，极度脆弱 |
| `AccessibilityService.getWindows()` | 可以枚举，但 inapp 模式的设计目标就是**免无障碍权限** |

对比 accessibility 模式：它用 `AccessibilityService.getWindows()` 直接枚举进程内所有 Window，所以不需要宿主注册。但代价是**需要用户在系统设置里手动授权无障碍权限**。inapp 模式的核心价值就是免这个权限，代价就是需要宿主配合注册弹窗。

从代码看 `dispatchPointer` 的候选列表构建：
```kotlin
val candidates = mutableListOf<View>()
act.window?.decorView?.let { candidates.add(it) }          // ① 主 Window — SDK 能自己拿到
candidates.addAll(RdForegroundActivityTracker.snapshotDialogDecorViews())  // ② 弹窗 — 必须有人注册过
val dv = candidates.reversed().firstOrNull { v ->
    v.isShown && hitTestView(v, x, y)                       // 按坐标命中
}
```
- **①** 主 Window：SDK 通过 `activity.window.decorView` 自动获取，无需宿主干预
- **②** 弹窗 Window：只能从 `dialogDecorViews` 列表里取，这个列表**只能靠宿主调 `registerDialogWindow` 填充**

如果宿主没注册，`snapshotDialogDecorViews()` 返回空列表，`candidates` 里只有主 Window，坐标命中弹窗区域时会 dispatch 到主 Window（被弹窗遮挡的部分），点击落在弹窗"背后"，弹窗收不到事件。

> **总结**：`AccessibilityService` 能枚举所有 Window → accessibility 模式免注册；Android 无此 API → inapp 模式需宿主注册。这是 inapp 模式"免权限"这个核心优势的**唯一代价**。

#### 为什么注册时机是 show/dismiss？

| 时机 | 动作 | 原因 |
|------|------|------|
| `setOnShowListener` → `registerDialogWindow` | Dialog 的 Window 已创建且 DecorView 可用 | `create()` 后 `show()` 前 Window 可能还没创建；`onShow` 时才保证 `dialog.window` 非空 |
| `setOnDismissListener` → `unregisterDialogWindow` | Dialog 销毁，DecorView 即将失效 | 不注销会导致 `snapshotDialogDecorViews()` 返回已销毁的 View，`dispatchTouchEvent` 到已 detached 的 View 会崩溃或静默失败 |

`snapshotDialogDecorViews()` 里虽然有 `it.isShown` 过滤，但 `isShown` 在 dismiss 后不一定立即变 false（取决于时序），所以手动注销更安全。

### 2.4 键盘输入

**问题**：PC 控制端默认用 Translate 模式发 KeyEvent（含 `chr` unicode 值），不是物理按键码。直接 `dispatchKeyEvent` 到 DecorView **不会在 EditText 产生文本**——Android 的文本输入走 `InputConnection`。

**解决**：对所有含 `chr`/`seq` 的 keyEvent，用 `InputConnection.commitText` 提交文本：

```kotlin
// 解析文本
var textToCommit: String? = null
if (keyEvent.hasSeq()) {
    textToCommit = keyEvent.seq
} else if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
    textToCommit = String(Character.toChars(keyEvent.getChr()))  // unicode → 字符
}

// 提交到焦点 EditText
val focused = findFocusedTextView(act)       // 优先在弹窗里找
if (focused != null && focused.onCheckIsTextEditor()) {
    val ic = focused.onCreateInputConnection(EditorInfo())
    ic?.commitText(textToCommit, 1)
}
```

`findFocusedTextView` 优先在 dialog window 里 `findFocus()`（弹窗里的 EditText 焦点在弹窗 window 而非 Activity window）。

> 注意：Android 13+（API 33）且存在文本时，跳过 `KeyEventConverter.toAndroidKeyEvent`（不构造物理按键 KeyEvent），避免干扰 `commitText` 的结果。

### 2.5 触摸事件（触控板/手机端控制）

触控板手势 `TOUCH_PAN_*` 与鼠标不同：
- `TOUCH_PAN_START(4)`：绝对起始坐标 → ACTION_DOWN
- `TOUCH_PAN_UPDATE(5)`：**增量 delta**，从前一位置减去 → ACTION_MOVE
- `TOUCH_PAN_END(6)`：最终绝对坐标 → ACTION_UP

---

## 3. accessibility 模式（已有，保留不动）

走原始 RustDesk 的 `InputService`（继承 `AccessibilityService`）：
- `dispatchGesture()` 注入手势（系统级，可跨 App）
- `performAction(ACTION_SET_TEXT)` 设置文本
- 需用户在系统设置授权无障碍

仅在 `inputMode="accessibility"` 时启用。

---

## 4. 两种模式对比

| 维度 | inapp（默认） | accessibility |
|------|--------------|---------------|
| 权限 | **无需任何特殊权限** | 需用户手动授权系统无障碍 |
| 控制范围 | **整个宿主 App**（所有 Activity + 弹窗） | 全系统所有 App |
| 注入方式 | `dispatchTouchEvent` / `commitText` | `dispatchGesture` / `performAction` |
| 系统手势(Home/Back) | 不支持（可由宿主自己处理） | 部分支持 |
| 鼠标滚轮/右键 | 暂不支持（静默丢弃） | 支持 |
| 宿主弹窗 | 需 `registerDialogWindow` 注册 | 自动支持 |
| 弹窗坐标 | SDK 自动转换屏幕→本地坐标 | 系统自动处理 |
| 键盘输入 | 所有模式(Translate/Legacy/Map)均支持 | 支持 |
| 适用场景 | Kiosk/独占 App；不需跨 App | 需控制系统其他 App |

---

## 5. 宿主 App 接入要点

### 5.1 启动被控端（默认 inapp）

```kotlin
RDMainRunner.startServerScreen(
    context, idServer, relayServer, key, id, password
    // inputMode 默认 "inapp"
)
```

### 5.2 弹窗注册（重要）

宿主 App 每个需要被远端控制的弹窗/BottomSheet，都要在 show/dismiss 时注册/注销：

```kotlin
import com.carriez.flutter_hbb.RdForegroundActivityTracker

val dialog = AlertDialog.Builder(this)....create()
dialog.setOnShowListener {
    dialog.window?.decorView?.let { RdForegroundActivityTracker.registerDialogWindow(it) }
}
dialog.setOnDismissListener {
    dialog.window?.decorView?.let { RdForegroundActivityTracker.unregisterDialogWindow(it) }
}
dialog.show()
```

> 若使用 `BottomSheetDialog` / `DialogFragment`，同理在 `onShow`/`onDismiss` 注册 window。

### 5.3 切换到 accessibility 模式（需跨 App 时）

```kotlin
RDMainRunner.startServerScreen(context, ..., inputMode = "accessibility")
```

---

## 6. 关键源码文件

| 文件 | 职责 |
|------|------|
| `RdInAppInputService.kt` (rdsdk) | inapp 核心：mask→MotionEvent 映射、位置跟踪、弹窗 hitTest、坐标转换、键盘文本提交 |
| `RdForegroundActivityTracker.kt` (rdsdk) | 跟踪前台 Activity + 维护 dialog window 列表 |
| `MainService.kt` (app) | `inputMode`/`inAppInputHandler` 存储 + `rustPointerInput` 按模式分发 |
| `RDMainActivity.kt` (rdsdk) | onCreate 设 inputMode、注册 tracker/handler；onDestroy 不清 handler；stop_service 清理 |
| `common.kt` (app) | `RdInputHandler` 接口（无状态，被两模块复用安全） |
| `InputService.kt` (app) | accessibility 模式（原有，不动） |
| `connection.rs` (rust) | 远端事件 → `call_main_service_pointer_input` JNI |

---

## 7. 生命周期管理

### 初始化（RDMainActivity.onCreate）

```
inputMode == "inapp" (默认):
  1. MainService.inputMode = 1
  2. RdInAppInputService.activity = this     // 备用引用
  3. MainService.inAppInputHandler = RdInAppInputService  // 注册处理器
  4. RdForegroundActivityTracker.register(application)    // 跟踪前台 Activity
```

### 销毁（RDMainActivity.onDestroy）

```
onDestroy:
  - 清除 RdInAppInputService.activity = null  // 仅清备用引用
  - 不清 MainService.inAppInputHandler        // handler 依赖 tracker.currentActivity
  - 不清 tracker                              // tracker 跟随 Application 生命周期
```

> **设计要点**：用户从 ServerPage 返回宿主 App 其他页面时，RDServerActivity 被销毁，但 `inAppInputHandler` 必须保持有效。handler 仅在 `stop_service` 时才清空。

### 停止服务（stop_service channel handler）

```
stop_service:
  1. MainService.inAppInputHandler = null    // 清空处理器
  2. RdInAppInputService.activity = null     // 清空引用
  3. mainService.destroy()                   // 停止服务
```

---

## 8. 踩坑记录

### 8.1 inputMode 被编译两份（inapp 模式完全不生效）

**现象**：设置 `inputMode=1` 后，`MainService.rustPointerInput` 里读到的 `inputMode` 始终为 0。

**根因**：`inputMode` 最初放在 `common.kt` 的顶层 `var`。`common.kt` 被 `:rdsdk` 通过 `sourceSets += '../app/src/main/kotlin'` 复用，导致 `:app` 和 `:rdsdk` 各编译一份。运行时 `:rdsdk` 的 `RDMainActivity` 写的是 `:rdsdk` 的副本，而 `:app` 的 `MainService` 读的是 `:app` 的副本——两个不同的静态变量。

**修复**：把 `inputMode` 移到 `MainService.companion`（只在 `:app` 模块），`RDMainActivity` 通过 `MainService.inputMode` 读写。

### 8.2 鼠标点击位置始终在左上角

**现象**：PC 端点击，被控端收到的触摸事件坐标是 (0,0)。

**根因**：PC 控制端 `sessionSendMouse({type:'down'})` 不携带 x/y（protobuf 默认 0）。被控端直接用事件的 x/y 就是 0,0。

**修复**：用 `mask=0`（纯移动）事件持续跟踪 `mouseX`/`mouseY`；DOWN/UP 时使用跟踪到的位置。

### 8.3 切换到宿主 App 其他页面后控制失效

**现象**：在 RustDesk ServerPage 上可控，返回宿主 App 设备列表页后失效。

**根因**：`RDMainActivity.onDestroy` 把 `MainService.inAppInputHandler` 置为 null。从 ServerPage 返回时 RDServerActivity 被销毁，handler 被清空。

**修复**：`onDestroy` 不清 `inAppInputHandler`，仅在 `stop_service` 时清空。改用 `RdForegroundActivityTracker.currentActivity` 定位目标 Activity。

### 8.4 弹窗无法点击 / 点击后自动关闭 / 无法输入

**现象**：AlertDialog 弹出后，远端点击输入框无反应，弹窗自动关闭。

**根因**：屏幕坐标直接 dispatch 给弹窗 DecorView，但 DecorView 期望本地坐标（相对自身左上角）。主 Activity DecorView 在 (0,0) 全屏所以正常；弹窗是悬浮窗口有偏移，触摸点落在弹窗"外面"，AlertDialog 当成"点击外部"自动关闭。

**修复**：dispatch 前用 `dv.getLocationOnScreen()` + `me.offsetLocation()` 将屏幕坐标转换为本地坐标。

### 8.5 键盘无法在弹窗 EditText 中输入

**现象**：弹窗里的 EditText 无法通过远端键盘输入文字。

**根因**：两个问题叠加：
1. 仅 Legacy 模式取 `chr` 做文本提交，PC 默认 Translate 模式不工作
2. `findFocusedTextView` 只在 Activity window 找焦点，弹窗 EditText 的焦点在 dialog window

**修复**：
1. 所有模式（Translate/Legacy/Map）都取 `chr`/`seq` 做 `commitText`
2. `findFocusedTextView` 优先在 dialog window 里 `findFocus()`

---

## 9. 排查指南

logcat 关注以下 tag：

```
adb logcat -s rd-inapp-input:* rd-fg-tracker:* LOG_SERVICE:* mRDMainActivity:*
```

| 现象 | 检查 |
|------|------|
| 点击完全无反应 | `rd-inapp-input: onMouseInput DOWN` 是否出现？`dispatchPointer target=...` 目标对不对？ |
| 点击位置不对 | `onMouseInput DOWN -> mx=... my=...` 位置是否正确？`SCREEN_INFO.scale` 是否=1？ |
| 切页面后失效 | `MainService.inAppInputHandler` 是否在 onDestroy 被误清？(已修复) |
| 弹窗点击无效 | `rd-fg-tracker: dialog window +` 是否出现？未注册则需宿主调 `registerDialogWindow` |
| 弹窗点击后自动关闭 | 坐标转换是否生效？检查 `dispatchTouchEvent result=... localX=... localY=` 中 localX/localY 是否在弹窗范围内 |
| 键盘不输入 | `commitText 'x' to ...` 是否出现？`findFocusedTextView` 是否找到 EditText？ |
| `onResume: inputPer=false` | `MainService.inputMode` 是否=1？(检查 onCreate 日志) |

---

## 10. 演进历史

| Commit | 内容 |
|--------|------|
| `c9e4aa9` | 基础实现：`RdInAppInputService` + `RdInputHandler` 接口 |
| `b229219` | 修复 `inputMode` 被编译两份 |
| `21777ff` | 修复鼠标位置跟踪（DOWN/UP 事件 x=0,y=0） |
| `2eae56d` | `RdForegroundActivityTracker` 控制整个宿主 App |
| `4c70f09` | 修复切页面后 handler 被 onDestroy 误清 |
| `2a8c706` | 弹窗可控 + 键盘全模式支持 |
| `a95c804` | 新增实现文档 |
| `ff68747` | 修复弹窗坐标偏移（屏幕→本地坐标转换） |
