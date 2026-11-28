# AGENTS.md

面向在本仓库开发、审核或维护代码的 AI Coding Agent 与人类贡献者。（若目标是通过 ADB 远程给电视装机或配权限，见 [`LLM.md`](./LLM.md)。）

## Checks

- **前端与仓库守卫**（修改 `parent-ui/`、`app/web/`、`:parent` 清单时必跑）：
  ```bash
  cd parent-ui && npm test && npm run build
  ```
  `npm test` 已包含 TypeScript 类型检查、业务单测与仓库静态守卫（ES5 语法校验、禁 `crypto.randomUUID()`、Token 约束与家长端权限红线）。
- **Android 单元测试与编译**（修改 `app/native/` 时必跑，建议 `--no-daemon` 顺序执行）：
  ```bash
  cd app/native
  gradle testTvDebugUnitTest testMobileDebugUnitTest :parent:testDebugUnitTest assembleTvDebug assembleMobileDebug :parent:assembleDebug --no-daemon
  ```
  涉及联网、更新、AI/朗读或本地隐私守卫改动时，补验 EU 仅本地隐私版编译：
  ```bash
  gradle assembleTvDebug assembleMobileDebug -PlocalOnly=true --no-daemon
  ```
- **真机 / 模拟器专项仪器回归**（修改 `LearningEngine`、`LocalStore`、`GateService`、`TimePolicy` 等管控主链路时按需挑项跑，均使用独立临时库，不污染家庭数据）：
  ```bash
  adb shell am instrument -w -e remoteOverride 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
  adb shell am instrument -w -e clockQuota 1     com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
  adb shell am instrument -w -e st60x 1          com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
  ```
- 检查已通过且代码未再改动时，不要重复空跑；绝不提交构建产物（`admin/`、`app/native/app/src/main/assets/`、`*.apk`、`local.properties`）。

## Repo Rules

- **保持 `main` 随时可发版**；不要破坏 CI。
- **版本与发版由维护者统一控制**：日常功能 / 修复 PR 不要擅自改 `versionCode` / `versionName` 或打 tag；默认仅递进补丁位（`v0.5.x`）；`:parent` 自动同步 `:app` 版本号；`release.yml` 在构建前强校验 Tag 与 `versionName` 一致、`versionCode` 严格递增，自动输出 `SHA256SUMS.txt` 与变更对比链接，且仅限主仓库互斥执行。
- **测试是例外，不是样板任务**：优先通过真实运行（浏览器预览、ADB 实机、仪器专项）证明改动有效。只在**静默回归代价高**的逻辑上补单元测试：
  - 协议编解码与加密（ADB 握手/RSA 签名、UDP 发现、导入导出解析）
  - 安全边界（RFC1918 私网校验、三类令牌鉴权隔离、路径穿越、EU 零外联守卫）
  - 持久化与迁移（SQLite `onUpgrade`、备份合并、多孩子数据隔离）
  - 状态机与并发不变量（额度/跨日/网络校时/关机解锁/错题加练）
  
  严禁为按钮文案、常量、第三方库行为或无意义 mock 链条堆单测；改动时如遇到低价值测试，宁可删除或重写为不变量测试。细则见 [`docs/agents-md-refs/test-antipatterns.md`](./docs/agents-md-refs/test-antipatterns.md)。

## Design System

所有 UI 改动（`parent-ui/`、`app/web/`、`app/native/parent/`）均须遵循 [`docs/STYLEGUIDE.md`](./docs/STYLEGUIDE.md)。
`parent-ui/src/theme.css` 是视觉 Token 唯一真相源；优先复用 `parent-ui/src/components/ui/` 原语，不要在业务组件里发明新色值、字号或阴影层级。

## Code Style

- **先复用再新建**：在已有抽象上扩展，不要平行造第二套逻辑。
- **注释写「为什么」不写「做了什么」**：代码本身清楚时不要写废话注释；文档、注释与提交信息统一中文，面向家长的文案避免工程术语。
- **禁止模糊模块命名**：不要新建 `utils`、`helpers`、`common`、`misc` 等垃圾桶文件；按具体领域概念命名（例如 `watch-gap`、`adb-crypto`、`question-import`）。新增文件尽量保持职责单一、控制体量，避免继续往已有超大文件里塞无关逻辑。
- **Kotlin 零三方运行时依赖**：只使用 Kotlin 标准库 + Android SDK（`:app` 仅保留已有 Bugly AAR；`:parent` 必须严格零依赖）。
- **`app/web/` 严格 ES5**：目标设备包含老旧 Android 5/6 系统 WebView，只用 `var` / `function`，禁止箭头函数、`const`/`let`、`?.`、`??`、模板字符串与构建压缩步骤。
- **统一路由与鉴权**：新增设备端 HTTP 接口必须在 `LanServer.registerRoutes()` 登记，鉴权逻辑统一收口在 `LanServer.checkApiAccess()`。

## First-Class Citizens

每次改动都必须默认评估这五个一等公民，不能顾此失彼：

1. **Android TV 遥控器 & 老旧系统（API 21+）**：方向键焦点可达、不能吞电源键、无触屏也能完整操作、兼容低版本 WebView 与 SQLite。
2. **手机 / 平板触屏（Mobile Flavor）**：小屏视口不溢出、悬停操作在触屏可见、支持软件盘弹出不遮挡。
3. **EU 仅本地隐私版（`-PlocalOnly=true`）**：任何新增外联（更新、在线题库、节假日、云端 AI/TTS）都必须受 `LocalOnlyGuard` 拦截，绝不静默发公网请求。
4. **局域网纯 HTTP 家长面板（`http://<lan-ip>:8099/admin/`）**：运行在浏览器**非安全上下文（Non-Secure Context）**下，禁止调用 `crypto.randomUUID()`、`navigator.clipboard`（需兜底）等 HTTPS-only API。
5. **独立家长端 App（`:parent`）**：严格保持**零敏感权限红线**（清单仅允许 `INTERNET` 和 `ACCESS_NETWORK_STATE`），绝不引入无障碍、悬浮窗、设备管理员或后台常驻服务。

## Footguns（按需查阅）

仅在改动涉及对应领域时阅读，不要盲目全量读入上下文：

- 改动 TV 悬浮窗、生命周期、安装更新、前台检测、时钟/开机计数或 WebView：读 [`docs/agents-md-refs/android-tv-footguns.md`](./docs/agents-md-refs/android-tv-footguns.md)
- 改动联网请求、局域网接口、令牌鉴权、备份导入导出、EU 隐私版或 `:parent` ADB 连接：读 [`docs/agents-md-refs/local-only-and-lan-security.md`](./docs/agents-md-refs/local-only-and-lan-security.md)
- 编写或审查任何自动化测试：读 [`docs/agents-md-refs/test-antipatterns.md`](./docs/agents-md-refs/test-antipatterns.md)
- 查完整 Android 模块职责地图：读 [`app/native/app/src/main/java/com/studyontv/tv/MODULES.md`](./app/native/app/src/main/java/com/studyontv/tv/MODULES.md)
