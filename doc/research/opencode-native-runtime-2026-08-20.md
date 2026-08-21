---
status: current-evidence
authority: local-and-official-research
source_ref: opencode-native-runtime-2026-08-20
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# OpenCode/本机 WS 原生交互核验

本文明确区分官方事实、本机实测事实和 Workbench 设计决定。访问和实测日期均为 2026-08-20。

## 1. 官方事实

核验基线是 OpenCode 官方仓库 commit [`b155b156`](https://github.com/anomalyco/opencode/tree/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4)，官方包与 SDK 版本均为 `1.18.19`。

| 能力 | 官方证据 | 事实结论 |
|---|---|---|
| headless/server | [Server 文档](https://opencode.ai/docs/server/)、[server routes](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/server/routes/instance/httpapi/groups/session.ts) | OpenCode 提供 HTTP/OpenAPI server，不要求调用方解析 CLI stdout。 |
| SDK/client | [SDK 文档](https://opencode.ai/docs/sdk/) | 官方 SDK 封装 Session、event、permission/question 等接口；Java 侧可按同一 OpenAPI 隔离一个薄 client。 |
| Session | [Session route source](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/server/routes/instance/httpapi/groups/session.ts) | 支持 create/list/get/messages/status、`prompt_async` 和 abort。 |
| SSE/event | [Event route source](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/server/routes/instance/httpapi/groups/event.ts) | `/event` 是原生 SSE；Session status、message/tool、permission/question 都可实时观察。 |
| status/reconciliation | [官方 CLI transport](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/cli/cmd/run/stream.transport.ts) | 官方 CLI 也以 status idle event 为主，并轮询 status 补偿丢失事件；这就是 Node 应复用的模式。 |
| permission | [Permissions 文档](https://opencode.ai/docs/permissions/)、[permission routes](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/server/routes/instance/httpapi/groups/permission.ts) | 可列出 pending request 并原生 reply once/always/reject。 |
| question | [question routes](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/server/routes/instance/httpapi/groups/question.ts) | 可列出、reply 或 reject question。 |
| cancel | [session abort route](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/server/routes/instance/httpapi/groups/session.ts) | cancel 应调用 `session.abort`，不应只杀本地 CLI 子进程。 |
| structured output | [prompt source](https://github.com/anomalyco/opencode/blob/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4/packages/opencode/src/session/prompt.ts) | 支持 JSON Schema Structured Output；它是结构化回答能力，不是外部业务验收证明。 |

`SessionStatus` 官方枚举只有 `idle / retry / busy`。因此 `idle` 是 Runtime 停止生成/调用工具的技术状态，不是 `PASSED`。

## 2. 本机 WS 实测事实

- 命令入口：`D:\Users\ex_xiaolf7\.local\bin\ws.cmd`，最终执行 `D:\Users\ex_xiaolf7\.local\share\workspace-code-prd\bin\ws.exe`。
- 版本：`0.0.0--202608171122`；本机 Midea extension 是 `midea-workspace.workspace-code 0.12.0`，其本地 package 依赖同时间戳的 `@opencode-ai/plugin`。
- 数据库名为 `opencode.db`；CLI 包含 `serve / acp / run / attach / session / export / mcp-serve`。
- 当前 0.12 server 进程以 `serve --hostname=127.0.0.1 --port=0` 启动，首次实测监听 `127.0.0.1:54014`；IDE 重启 server 后端口变为 58433，版本仍为 `0.0.0--202608171122`。另一个旧版本实例监听 4096。动态变化证明 Node 不能硬编码端口；本轮只读关联进程所有权与监听 socket，没有扫描、启动或重启 WS。
- 对 0.12 实例的只读请求实测：`/session/status -> 200 {}`、`/permission -> 200 []`、`/question -> 200 []`；此前已确认 `/session`、`/session/{id}`、message 和 `/event` SSE，首个 SSE 事件为 `server.connected`。
- 0.12 的 `GET /doc` 返回 200 OpenAPI，但只列出 37 个 global/Midea 路径，没有列出实际可用的 core `/session`、`/event`、`/permission`、`/question` 路径。因此不能用该文档声称完整 capability discovery；Node 启动只读探测安全接口，其余接口在首次调用时严格校验状态码、SSE Content-Type 和 JSON schema。
- 单独再启动 `ws serve` 会因 named-pipe singleton 与已有 Midea server 冲突。因此不能假定 Node 总能拥有 server 进程，也不能硬编码 4096。
- `ws run --format json` 当前输出过 `step_start / tool_use / step_finish / text`，这是 vendor CLI 表面，不等同于官方 `1.18.19` event schema。
- 0.12 `ws.exe` 内置源码确认 `prompt_async` 支持显式 `agent` 和 `model: {providerID, modelID}`；本机 `/agent` 和 `/provider` 可列出并验证这些目标。当前 provider 为 `workspace`，其中 `gpt-5.6-luna` 可用。
- 2026-08-21 使用重建后的真实 Java Node client 连接 0.12 实例：无工具 `READY` Session 完成 create → SSE → `prompt_async` → messages → `session.idle`；早期两个临时文件工具任务完成 read/write 后分别持续 `busy` 5 分钟和 2 分钟，原生 abort 与 Node cancel 均能停止并清理。随后显式 `gpt-5.6-luna`、显式 `hw-glm-5`、隐式目标、真实 Node client/SSE 和最新显式 target canary 共五组工具任务都正常进入 `idle`。最新当前代码 Session `ses_fddb09a91ffedomiwlf9fDZZRy` 产出 `{"sum":42}` 并通过独立验收；不存在的模型在 Session 创建前由 capability Gate 拒绝。Session `ses_fdda36619ffetYCw5BMZWxFgZV` 还完成原生 question → Node reply → 同 Session 继续 → artifact → idle，并由新 Adapter 只读 reattach 为原已完成 Session。早期卡住因此保留为间歇性上游故障样本，不能稳定归咎单一模型。详见 `testing/e2e-native-opencode-real-ws.md`。

这些事实证明本机实现属于 OpenCode 能力体系，但不证明它与当前上游版本逐字段相同。Node 必须精确 version Gate，并由 fake/真实本机测试证明所用子集。

## 3. 原生能力与旧自建能力对照

| OpenCode/WS 原生能力 | 旧自建能力 | 重建决定 |
|---|---|---|
| `POST /session`、Session ID | CLI 首轮观察 sessionID，再用 `--session` 拼接 | 删除 CLI 路径；Session ID 仅本地持久化。 |
| `prompt_async` | 每 Turn 启动一个 `ws run` | 删除固定 Turn；Mission 默认只 prompt 一次。 |
| `/event` SSE | stdout NDJSON 主控制流 | 以 SSE 为实时主通道，raw event 仍只作本地证据。 |
| `/session/status` | 子进程退出/观察窗 | status 是对账真值；退出码和固定时间窗不再定义完成。 |
| message list/get | assistant 摘要与 stdout 扫描 | 直接读取原生 message；不扫描 prose 中嵌入 JSON。 |
| permission/question list + reply/reject | fake Interaction、pause/checkpoint/resume | 删除假协议；薄映射到既有 Service Interaction 绑定。 |
| `session.abort` | destroy CLI process | 改为原生 abort；失败则 UNKNOWN。 |
| Session get + status + event 重订阅 | 自定义 inspect/resume | 薄封装 reattach/reconciliation，禁止新建替代 Session。 |
| Structured Output | `lingfeng.terminal`、digest 回显、`SUCCEEDED/PASSED` | 删除；Structured Output 只进入证据，验收独立。 |
| messages/diff/tool evidence | terminal 摘要代表验收 | 完整证据留 Node；AcceptanceEvaluator 客观核验。 |

旧交互复杂的根因不是控制环本身，而是用一次性 CLI stdout 模拟长期 Session API，再让模型输出业务控制协议来填补 status、Interaction 和 acceptance 的缺口。

## 4. 目标时序

```text
Service                    Node                              local WS/OpenCode
  | START_RUN (poll)         |                                      |
  |<-- COMMAND_STORED -------|                                      |
  |                          |-- health/version/capability Gate --->|
  |                          |-- POST /session --------------------->|
  |                          |-- durable bind server/ws/session ----|
  |<-- RUN_STARTED ----------|-- GET /event (SSE) ----------------->|
  |                          |-- POST prompt_async (once) ---------->|
  |                          |<-- busy/retry/message/tool events ----|
  |<-- short progress -------|                                      |
  |                          |<-- permission/question.asked ---------|
  |<-- INTERACTION_REQUESTED-|                                      |
  | PROVIDE_RESPONSE (poll)  |-- native reply/reject -------------->|
  |<-- RESPONSE_CONSUMED ----|                                      |
  |                          |<-- session.status=idle ---------------|
  |                          |-- status/messages/pending reconcile ->|
  |                          |-- AcceptanceEvaluator(local evidence) |
  |<-- RUN_TERMINAL ---------|                                      |
```

Service 不回连 Node；OpenCode 的 SSE 只存在于 loopback Node→WS 边界。

## 5. 已重建与仍需完成

当前工作树已经：

- 删除 CLI Runtime、terminal interpreter、固定 Turn 和 `control_turn` 新建逻辑；
- 增加 OpenCode HTTP/SSE client、显式 loopback endpoint/version Gate；
- 显式配置并 Gate `agent/provider/model`，`prompt_async` 不再依赖 IDE 隐式目标；
- 增加 Session 的 server identity/version/workspace/Session ID 本地绑定；
- 将 Supervisor 改为一次 Mission prompt、原生 Interaction、abort、reattach 和独立 acceptance；
- 用 fake OpenCode HTTP/client 测试替换旧 CLI stdout 测试；
- 保持 Service/Web v2 合同和最小数据投影不变。

仍需真实实测/后续开发：

1. Midea 0.12 的受支持 endpoint discovery 或专属 server ownership 接口；当前只能显式配置动态 endpoint。
2. 已完成无工具 Session create → SSE → prompt_async → idle → messages、工具、abort 和当前显式 target canary；早期两个工具后长期 `busy` 的失败样本仍要求用连续真实 Run 做稳定性 Gate，不能通过 Node 猜测完成来掩盖。
3. 为真实 executionProfile 实现客观 AcceptanceEvaluator；在此之前真实 WS 即使 idle 也必须落 `SUCCEEDED/UNKNOWN -> uncertain`，工具后不 idle 则保持运行或按超时进入 uncertain/cancelled，不能 completed。
4. 真实 question event/reply 已通过；仍需对真实 permission 做一次临时 Session 实测。
5. 已完成原 Session 的 completed reattach；仍需验证 SSE EOF、活动 Run 的 server/Node 重启和 pending Interaction 对账，确认同一 Session 身份后才宣称活动恢复可用。

本次没有兼容/迁移策略：新构建使用新的本地状态目录和新配置，旧 CLI 实现只从 Git 历史与既有 E2E 证据审计，不进入运行路径。
