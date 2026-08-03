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
      3. mainHandler.post { dv.dispatchTouchEvent(me) }
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
| `RdInAppInputService.kt` (rdsdk) | inapp 核心：mask→MotionEvent 映射、位置跟踪、dispatch、键盘文本提交 |
| `RdForegroundActivityTracker.kt` (rdsdk) | 跟踪前台 Activity + 维护 dialog window 列表 |
| `MainService.kt` (app) | `inputMode`/`inAppInputHandler` 存储 + `rustPointerInput` 按模式分发 |
| `RDMainActivity.kt` (rdsdk) | onCreate 设 inputMode、注册 tracker/handler；stop_service 清理 |
| `InputService.kt` (app) | accessibility 模式（原有，不动） |
| `common.kt` (app) | `RdInputHandler` 接口（无状态，被两模块复用安全） |
| `connection.rs` (rust) | 远端事件 → `call_main_service_pointer_input` JNI |

---

## 7. 排查指南

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
| 键盘不输入 | `commitText 'x' to ...` 是否出现？`findFocusedTextView` 是否找到 EditText？ |
| `onResume: inputPer=false` | `MainService.inputMode` 是否=1？(检查 onCreate 日志) |
