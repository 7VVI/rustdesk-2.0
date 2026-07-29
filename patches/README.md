# patches

对 git 子模块的本地改动补丁（子模块 `libs/hbb_common` 指向上游 `rustdesk/hbb_common`，
无法直接推送到本仓库，故以补丁形式随主仓库一起版本化）。

## 0001-hbb_common-allow-numeric-custom-id.patch

放开 RustDesk 自定义设备 id 的格式校验 `is_valid_custom_id`：
- 原：`^[a-zA-Z][\w-]{5,15}$`（必须字母开头，纯数字机号被拒）
- 新：`^[a-zA-Z0-9][\w-]{5,15}$`（允许数字开头，长度仍为 6–16）

目的：让纯数字机号（≥6 位）可作为设备 id，通过 `main_change_id` 注册。
少于 6 位的机号仍会被拒（长度限制未变）。

### 应用方法

```bash
cd libs/hbb_common
git apply ../../patches/0001-hbb_common-allow-numeric-custom-id.patch
# 或使用 git am 保留提交信息：
# git am ../../patches/0001-hbb_common-allow-numeric-custom-id.patch
```

应用后需重新编译 `librustdesk.so`（arm64/armv7/x86_64）：

```bash
export VCPKG_ROOT=/opt/vcpkg
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/25.2.9519653
# 依赖（首次或缺失时）
./flutter/build_android_deps.sh arm64-v8a
# 编译
cargo ndk --platform 21 --target aarch64-linux-android build --locked --release --features flutter,hwcodec
cp target/aarch64-linux-android/release/liblibrustdesk.so flutter/android/app/src/main/jniLibs/arm64-v8a/librustdesk.so
```

> 若要正式纳入版本控制，建议 fork `rustdesk/hbb_common` 到自己的账号，
> 将 `.gitmodules` 的 url 改为该 fork，在子模块内提交并推送后，再在主仓库更新子模块指针。
