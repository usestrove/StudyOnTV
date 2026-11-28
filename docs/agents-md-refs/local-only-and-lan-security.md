# EU 仅本地隐私版与局域网安全守则

> 改动 `LanServer`、`LocalOnlyGuard`、`LocalStore` 备份导入导出、网络请求、`Update` / `BankMarket` / `SpeechReader` 或 `:parent` 模块前必读。

## 1. EU 仅本地隐私版（`-PlocalOnly=true`）红线

- 所有公网外联入口（在线更新、应用内安装、在线题库市场、节假日同步、云端 AI 出题/批改、云端语音朗读、崩溃上报）必须首先经过 `LocalOnlyGuard` 检查。
- 在 Local-Only 模式下：
  - 仅允许连接**家庭私有局域网 IP**（如本地 Ollama、家庭多台 StudyOnTV 间同步、局域网家长后台）；
  - 备份恢复必须调用 `sanitizeKvValueForLocalOnly` / `sanitizeBankAutomationForLocalOnly`，防止恢复普通版备份后意外带入公网接口地址。

## 2. 局域网 SSRF 与私有地址校验

- 凡接收用户输入的局域网地址（多设备同步、家长端手动添加 IP、无线 ADB 连接、本地 Ollama 地址），必须严格按 **RFC1918 私有 IPv4 字面量**校验（`10/8`、`172.16/12`、`192.168/16`）：
  - 拒绝公网 IP、回环 `127.x`（除非显式本机服务）、链路本地 `169.254.x`、前导零八进制绕过（如 `192.168.01.1`）以及任意域名；
  - 端口限定合法范围（`1024..65535`）；
  - 局域网 HTTP 请求一律显式传 `Proxy.NO_PROXY` 且 `instanceFollowRedirects = false`，防止手机开代理时把局域网流量/令牌外泄或被 302 重定向到公网。

## 3. 三类令牌鉴权隔离

`LanServer.checkApiAccess()` 是唯一鉴权裁决点：
- **家长会话令牌（`Authorization`）**：完整管理权限；
- **设备令牌（`X-Device-Token`，仅 TV/平板学习页 WebView）**：严格白名单（`isDeviceRouteAllowed`），只允许读取学习状态、切孩子与答题，绝不允许修改规则、题库、密码或调用 `/api/remote`；
- **第三方集成令牌（Home Assistant / Telegram）**：严格按 `read` / `control` 权限位放行白名单路由。

## 4. 密钥、密码与日志脱敏

- 管理密码、AI API Key、云端 TTS Secret、集成令牌与会话 Token **绝不回显明文**（前端只返回 `hasKey` / `*Set` 布尔标记），绝不写入崩溃日志、诊断日志、截图或公开备份的 `kv` 导出（参见 `LocalStore.SECRET_KV_KEYS`）。
- 瞬态状态（`active_round`、`remote_override`、`rest_deadline`、`boot_quota_ms` 等）属于本机时钟/重启上下文，备份导出与合并一律跳过（`EPHEMERAL_KV_KEYS`）。

## 5. 家长端独立 App（`:parent`）红线

- `:parent` 面向家长日常主力手机，**绝不引入杀软敏感权限**：`AndroidManifest.xml` 仅允许 `INTERNET` 与 `ACCESS_NETWORK_STATE`。
- 无线 ADB（`TCP:5555`）仅限连接私有局域网 IP；推送的临时 APK 在 `finally` 中强制清理 `/data/local/tmp/studyontv-install.apk`；装机完成后明确提醒家长关闭电视 ADB 调试开关。
- 修改电视无障碍列表（`enabled_accessibility_services`）必须与现有值做**非破坏性并集**，并校验合法组件字符集防命令注入，绝不覆盖用户既有的 TalkBack 或电视语音助手。
