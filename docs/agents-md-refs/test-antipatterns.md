# 测试反模式清单（Test Anti-Patterns）

> 借鉴自 `stablyai/orca`，针对 StudyOnTV（Kotlin JVM 单测 + Android 仪器回归 + `parent-ui` Node 测试）定制。

## 核心原则

默认不写样板测试：通过真实运行（预览页 / 真机 / ADB 验证）证明功能正常。自动化测试池应保持精炼、高信噪比，只守护**静默回归代价高**的不变量：

- 协议、解析器与加密（ADB 协议与 RSA 签名、UDP 发现、Word/PDF/JSON 题目导入、WAV/TTS 解析）
- 安全与权限边界（RFC1918 私网校验、三类令牌路由隔离、EU Local-Only 守卫、密钥脱敏）
- 数据持久化与升级（SQLite `onUpgrade` 迁移、备份合并、多孩子隔离）
- 管控核心状态机（跨日与网络校时、免费时长与激励余额、关机解锁、错题加练队列）

**保留一条测试的 4 条标准**：
1. 回退本次修复代码时，该测试**必然失败**；
2. 期望值直接硬编码（不调用生产函数现场算期望值）；
3. 放在拥有该行为的最便宜一层（能纯 JVM/Node 跑的绝不拖进仪器测试，能跑独立临时 DB 仪器回归的绝不污染真实家庭数据）；
4. 规模与改动相称（一行根因修复配 1–2 个精准用例，而非铺几十个排列组合）。

## 禁止出现的测试写法

以下测试信噪比极低、维护成本高，评审时应拒绝或删除：

- **复写生产逻辑作预言机**：`assertEquals(buildX(input), actual)` 这类「用自己测自己」的断言。
- **空洞断言**：`assertTrue(true)`、仅 `assertNotNull(x)`、`assertTrue(a || b)`、不限定异常类型和信息的裸 `try { ... } catch (_: Exception)`。
- **只测 Mock 调用链**：全靠 mock 拼出返回值、仅验证 `verify(mock).foo()` 而没有跑真实业务代码。
- **用静态 DOM / 类名冒充 UI 覆盖**：在纯文本或 jsdom 里断言 Tailwind 类名、按钮文案或中文提示语（文案一润色就挂，却拦不住真正的交互 bug）。
- **测试常量、默认值清单或第三方库行为**：例如断言 Radix Select 聚焦机制、Zod 基础类型校验或枚举数量。
- **按真实墙上时间（Wall-Clock）或 `Thread.sleep` 断言性能**：CI 机器负载抖动必然导致偶发失败；涉及时钟和倒计时逻辑应注入可控时钟（如 `readClock`、`readBootCount`）。
- **为测试污染生产代码**：不要为了单测在生产类里塞 `*ForTests()` 后门分支或全局开关；应通过构造函数依赖注入（如 `LocalStore(..., readClock, readBootCount)`）或纯函数抽取实现可测性。
- **跨层重复测试同一契约**：同一个校验规则已经在底层状态机测过，不要在路由层、前端层再逐一机械重复。

## Android 仪器回归规范（`androidTest`）

- 必须使用独立临时数据库名（如 `remote-override-regression.db`），并在 `finally` 中 `store.close()` + `context.deleteDatabase(name)`，绝不读写用户真实题库与系统设置。
- 新增独立专项开关时同步挂入 `RegressionInstrumentation`，便于开发时 `-e <flag> 1` 秒级复现。
