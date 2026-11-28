# Android TV / 手机厂商踩坑速查（Footguns）

> 改动 `GateService`、`GateOverlay`、`MainActivity`、`ApkInstaller`、`LearningEngine`、`LocalStore`、`Foreground`、`MediaPause` 或 `app/web/` 前必读。

## 1. 在线更新与安装器

- **小米电视 Android 14 缺失 `CONFIRM_INSTALL`（#201 / #203）**：部分 ROM 的系统安装器未注册 `android.content.pm.action.CONFIRM_INSTALL`。若直接 `PackageInstaller.Session.commit()`，系统对同包执行 `force-stop` 会清空 `ApkProvider` 的临时 URI 授权，事后再退回 `ACTION_VIEW` 会直接 `SecurityException` 一闪而过。**必须先探测确认页存在**（且在 Manifest `<queries>` 声明），否则直接走 `ACTION_VIEW`。
- **Android 9 / 10 签名校验（#190）**：旧版 `getPackageArchiveInfo` 在某些固件上读不到 APK 证书集合，签名比对遇到空集合时只能视为「无法判定（`null`）」，绝不能误判为签名冲突拦截升级。
- **MIUI / HyperOS 后台拉起限制（#202）**：平板/手机后台由 HTTP 线程拉起 `UpdateActivity` 可能被厂商 `ActivityStarterImpl` 拦截；需要做好前台可见窗口或显式通知引导。

## 2. WebView、悬浮窗与焦点

- **缺失系统 WebView 崩溃（ST-619）**：极少数精简版/异常固件的 TV 上 `WebView` 构造会抛出 `MissingWebViewPackageException` / `InflateException`。`MainActivity` 的 `web` 必须保持可空（`WebView?`），`WebShell.createOrNull` 失败时走优雅降级提示，不能让进程直接崩溃。
- **悬浮窗 0 尺寸假成功（#110）**：部分电视 `WindowManager.addView` 不抛异常但窗口实际不可见（宽高 0）。必须校验真实可见性与 `pageReady`，不能只看 `attached`。
- **`GateOverlay` 复用 WebView 切路由（#198 审出）**：`suspend()` 会通过 `SOTV_PAUSE()` 把 JS 侧 `pagePaused` 置为 `true`；下次 `show(newReason)` 若复用同一 WebView 并因 `reason` 变化触发 `loadUrl("#/...")`（仅 hash 切换、不会重载文档），**必须同步调用 `SOTV_RESUME()`**，否则轮询与时钟会永久停摆。
- **全屏页吞遥控器电源键（#143）**：创维 / 酷开等机型在全屏学习页中严禁拦截 `KEYCODE_POWER` / `KEYCODE_SLEEP`，收到电源键时主动退出全屏把关机权交还固件。
- **老旧电视 WebView（API 21–25）**：`app/web/` 必须严格 ES5（无箭头函数、`let`/`const`、`?.`、`??`）；API < 26 悬浮窗 WebView 使用软件层兜底避免黑屏。

## 3. 前台检测、关机与保活

- **海信 VIDAA（#66）**：看视频时前台包名始终上报桌面包名 → 不能只靠前台包名，需结合媒体播放状态兜底计时。
- **TCL 关机（#57）**：遥控器关机后 `PowerManager.isInteractive` 仍可能返回 `true` → 必须同时结合屏幕状态广播与 `Display.STATE_ON`。
- **小米等待机不发开机广播**：不能单靠 `BootReceiver`；需要 `RevivalListener` 与 `Watchdog` 双重保活。
- **Android 13+ 侧载受限设置**：侧载包首次开无障碍可能被系统锁死，不要循环死写 `enabled_accessibility_services`，应引导用户在应用详情页右上角「允许受限设置」。

## 4. 时钟、重启计数与数据库兼容

- **冷启动错误时钟 & 未来日期回正（#167 / #200）**：无电池的电视/盒子断电重启后可能先读出出厂日期或错误未来日期，联网校时后再跳回正确日期。所有日切逻辑必须通过 `DeviceClock` 区分 `NETWORK` 与 `SYSTEM` 来源，回正未来记账日时保留已结算额度和组数，并记录 `usage_day_rejected_system` 防止退回系统时钟时重复发额度。
- **关机失效判断（#199）**：`untilShutdown` 解锁依赖 `Settings.Global.boot_count` 与 `SystemClock.elapsedRealtime()`，持久化 `until=-1 / untilElapsed=-1` 以防旧版降级误读成无限解锁；`boot_count < 0`（部分 Android 5/6 设备）必须拒绝该模式并提示改用定时解锁。
- **Android 5（API 21）SQLite**：系统内置 SQLite **不支持 `JSON_EXTRACT`**；任何 `onUpgrade` 迁移与查询禁止依赖 SQLite JSON 函数。
- **ADB `appops` 与 `cmd` 兼容**：API 21–23 没有 `/system/bin/cmd`，统一优先使用 `appops set <pkg> GET_USAGE_STATS allow`（注意操作名是 `GET_USAGE_STATS` 而非 `PACKAGE_USAGE_STATS`），API 24+ 失败时再回退 `cmd appops`。
