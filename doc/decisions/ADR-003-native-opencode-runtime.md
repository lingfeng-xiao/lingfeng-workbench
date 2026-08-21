---
status: authoritative
authority: DF-0.4-native-opencode
source_ref: ADR-003
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# ADR-003：Node 使用 OpenCode 原生 Server API

## 背景

旧 WS Adapter 为每个固定 Turn 启动一次 `ws run --format json`，再通过 `--session` 续接会话。为了从 CLI 文本推断控制状态，Node 又叠加了 Mission digest 回显、三 Turn、`lingfeng.terminal`、摘要内 JSON 扫描和 `SUCCEEDED/PASSED` 解释。这些机制复制了 OpenCode 已提供的 Session、异步 prompt、SSE、status、permission/question 和 abort 能力，并把业务验收错误地塞进 Runtime 传输协议。

本机 WS `0.0.0--202608171122` 确认源自 OpenCode 系列实现并提供上述 HTTP 接口，但它不是当前上游 `1.18.19` 的同版本发行物。多个 WS server 还可能同时监听不同 loopback 端口，因此不能通过名称或固定端口猜测目标实例。

## 决定

- Node 直接调用一个显式配置且通过健康、版本和能力 Gate 的本机 WS Server；不再执行 `ws run`。
- 一个 Run 创建一个 OpenCode Session，并只发送一次初始 Mission prompt。后续 message 只服务于真实追问或 Interaction，不是固定 Turn。
- Node 订阅原生 SSE，并以 `/session/status`、Session、message、permission/question 列表做断流后的 reconciliation。
- permission/question 的原生 request ID 是 Interaction 的本地相关标识；完整请求留在 Node，只向 Service 投影既有短 Interaction 合同。
- cancel 映射到 `session.abort`。Node 重启后只重新绑定原 Session；无法证明 Session 身份或目标 server 身份时进入 `uncertain`，不得新建替代 Session。
- Runtime 完成只表示 Session 回到 `idle` 且消息已完成，不表示 Mission 验收通过。独立 `AcceptanceEvaluator` 根据本地、可核验的测试/报告/人工证据给出 `PASSED/FAILED/UNKNOWN`。
- Service 继续是全局控制面，Node 继续是本机控制面；Service 不回连 Node，也不接收 OpenCode Session ID、原始事件、message、diff 或绝对路径。
- 不提供旧 CLI fallback、feature flag、双写、迁移或兼容模式。历史真实 E2E 只作为误区与回归证据保留。

## 组件所有权

- architecture：本 ADR、全局时序和 Node→Runtime 语义；Service→Node v2 合同不因 Runtime 更换而扩散字段。
- workbench-node：endpoint 配置/探测、OpenCode HTTP/SSE 客户端、Session 绑定、事件投影、Interaction、abort、reconciliation、验收边界和本地证据。
- workbench-service：保持既有 Run/Interaction/Outbox 业务状态机，不解释 OpenCode 状态。
- workbench-web：通过 Service Task API 提供业务操作和安全投影，不接触 Runtime。
- integration：用 fake OpenCode HTTP/SSE server 验证原生协议，不再伪造三 Turn 或 terminal 文本。

## 错误边界

- endpoint 缺失、非 loopback、健康失败、版本不匹配或关键接口缺失：Node 启动预检失败。
- SSE 断开：记录本地告警、重连，并以 status/messages/pending Interaction 对账；不能仅因断流判失败。
- `idle` 但没有可核验验收证据：`runtimeOutcome=SUCCEEDED`、`acceptanceStatus=UNKNOWN`，Run 为 `uncertain`。
- abort 请求失败或重启后 Session/Server 身份无法确认：结果为 `UNKNOWN`，不猜测取消成功。

## 官方证据基线

访问日期均为 2026-08-20：

- OpenCode Server：<https://opencode.ai/docs/server/>
- OpenCode SDK：<https://opencode.ai/docs/sdk/>
- OpenCode CLI：<https://opencode.ai/docs/cli/>
- OpenCode Permissions：<https://opencode.ai/docs/permissions/>
- 官方源码：<https://github.com/anomalyco/opencode/tree/b155b15694dbcc6768f11d2f25cc2bdd1f738ab4>

官方版本 `1.18.19` 证明能力和交互模式；本机 WS 的实际兼容性仍必须由 Node capability Gate 和本机集成测试证明，不能由版本名称外推。
