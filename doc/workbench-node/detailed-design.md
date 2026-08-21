---
status: authoritative
authority: DF-0.5-business-loop
source_ref: node-design-n4-task-context
owner: workbench-node
superseded_by: null
last_verified: 2026-08-21
---

# workbench-node 详细设计 N4

## 0. v0.5 P1 增量

Node Protocol v2 的 START_RUN 可选增加最多 16 个唯一 contextRefs；旧命令省略该字段仍合法。Node-only ContextRegistry 把 workspaceRef/contextRef 安全 alias 映射到既有本机路径，使用 `toRealPath()`、允许根、可读/可写检查阻止不存在路径和 symlink 逃逸；任何解析失败都必须在 `openSession` 前 fail closed，错误摘要不得含绝对路径。

解析后的 workspace/contextPaths 只进入 SessionContext 和一次 Mission prompt，Service/Web 永不接收路径、Session、原始事件、diff、日志或产物。恢复同一 Run 时重新解析同一 aliases，失败进入 uncertain，不创建替代 Session。

一个 Node 同时仍只执行一个 Run，但终态 Session 关闭后必须释放 active binding 并允许下一个 durable Run 串行启动。终态关闭期间到达的新 START_RUN 可排队一个；其它并发 Run 继续拒绝。runtime sink 绑定 expected runId，旧 Session 迟到事件不得污染后续 Run。

## 1. 职责与边界

Node 是本机控制面：可靠接收 Service 命令、把一个 Run 绑定到一个本机 OpenCode Session、保存完整证据，并向 Service 投影短状态。WS/OpenCode 执行源码读取、工具调用和模型对话。Node 不实现文件工具，也不把 OpenCode DTO、Session ID、message、diff 或绝对路径发送给 Service。

ServiceConnectionLoop、RunSupervisor、OpenCode 客户端三者必须异步解耦：网络轮询不等待 Agent，Runtime 客户端不更新业务数据库，Supervisor 不拼 HTTP URL 或解析上游 JSON。

## 2. 模块内分工

```text
connection
  ServiceConnectionLoop + Service HTTPS client

orchestration
  RunSupervisor（单 Run 串行队列）
  AcceptanceEvaluator（独立业务验收）

runtime/session
  收窄后的异步 Session SPI 与归一化事件

runtime/opencode
  OpenCodeClient
  HttpOpenCodeClient（HTTP、SSE、严格 JSON/状态码）
  RuntimeEventProjector（原生事件 -> 本地事件）

runtime/ws
  WsEndpointResolver（配置、loopback、health/version/capability Gate）
  WsSessionRuntimeAdapter（Session 绑定、SSE 重连、status/message 对账）

localstate
  command inbox、Run/Session/Interaction、event log、outbox、本地 evidence
```

## 3. Endpoint 与进程生命周期

首个重建版本只接受显式 `WORKBENCH_WS_BASE_URI`，URI 必须是 loopback HTTP(S)。Node 不扫描端口、不读取编辑器私有 IPC、不硬编码 4096，也不与已运行的 Midea WS 抢 singleton。

启动 Gate 顺序：

1. `GET /global/health`；
2. 校验健康状态和配置的 WS 精确版本；
3. 通过 `/agent` 和 `/provider` 验证配置的 agent/provider/model 存在；当前默认值为 `build / workspace / gpt-5.6-luna`，不得依赖 IDE 隐式选择；
4. 只读验证 status、pending permission/question；本机 `/doc` 未完整列出 core Session routes，create/prompt/event/abort 由严格状态码、Content-Type 和响应 schema 在首次使用时 fail closed；
5. 任一步不明确即拒绝启动，且不得创建 Session 或发送 prompt。

未来只有拿到 Midea 官方支持的发现/所有权接口后，才可增加自动发现或专属 server 启动；它不能退化成端口扫描。

## 4. Session 与一次 Mission prompt

START_RUN 流程：

```text
Service poll -> Node durable command/ACK
             -> resolve workspace
             -> POST /session
             -> durable bind(runId, server identity/version, workspace, sessionId)
             -> GET /event (SSE)
             -> POST /session/{id}/prompt_async（一次）
             -> status/event/message reconciliation
```

prompt 请求显式携带已通过启动 Gate 的 agent/provider/model，并包含 objective、acceptanceSummary、authorizedSideEffectsSummary 和 executionProfile。它不要求回显 digest，不要求输出 `lingfeng.terminal`，也不规定三 Turn。HTTP 204 仅表示 prompt 被接受。

## 5. 状态与完成

本机 Runtime 状态：

```text
opening -> idle -> busy/retry -> waiting_interaction -> idle
                                      \-> error
                                      \-> aborted
```

`session.status=idle` 触发 reconciliation；status map 显式返回 `idle` 时同样可以收敛。status map 暂时缺少绑定 Session 不能单独视为 idle，必须由 messages 证明最后一条 assistant message 已 completed 且 `finish != tool-calls`，否则保持 busy。收敛后读取 Session、messages、diff/本地报告并封存 Runtime evidence，随后 AcceptanceEvaluator 独立返回 `PASSED | FAILED | UNKNOWN`。

`idle` 可以支持 `runtimeOutcome=SUCCEEDED`，但不能自动产生 `PASSED`。默认 evaluator 在没有可客观核验证据时返回 UNKNOWN。业务 profile 可提供测试结果、结构化报告或人工验收 evaluator；模型自述和 Structured Output 只能作为证据输入，不能自行成为验收器。

## 6. SSE 与 reconciliation

- SSE 是低延迟主通道；所有原始 data 行先追加 `runtime-events.ndjson`。
- 只处理绑定 Session 的 `session.status`、message/tool、permission/question 和 error 事件。
- SSE EOF/解析失败不会直接终结 Run；同一 Session 同时最多存在一个重连计划，按 1/2/4/8/16/30 秒有界退避。同一断线窗口和 reconciliation 故障只投影一次短告警，Session 专属事件恢复后才重置退避，禁止用 Service 事件和本地 evidence 热循环。
- 每次重连、Node 恢复和 idle 前都调用 status/messages/pending permission/question 对账，补回错过事件。
- 高频 token/tool/diff 只留本机；Service 仍只收既有 phase/progress/Interaction/terminal 投影。

## 7. Interaction

原生 permission/question request ID 作为本地 Interaction 相关 ID，并同时作为现有 Service 合同要求的 checkpointId；这里的 checkpointId 是不可变响应绑定，不宣称 WS 创建了自定义 checkpoint。

| 原生请求 | Service allowedDecisions | 响应调用 |
|---|---|---|
| permission | APPROVE, REJECT | `/permission/{id}/reply`，`once` 或 `reject` |
| question | PROVIDE_INPUT, REJECT | `/question/{id}/reply` 或 `/reject` |

完整 pattern、metadata、questions 和 answers 留在 Node。响应命令先落盘；原生 API 成功后才发 `INTERACTION_RESPONSE_CONSUMED`。不再调用 fake pause/resume/checkpoint API。

## 8. 取消与恢复

- CANCEL_RUN 调用 `POST /session/{id}/abort`；成功后持久化 INTERRUPTED。请求失败则 UNKNOWN。
- Node 重启读取原 run/session/server/workspace 绑定，重新 health/version Gate、GET Session、重订阅 SSE 并 reconciliation。
- Session 不存在、server identity/version 改变或 workspace 不匹配时 UNKNOWN；禁止创建第二个 Session。
- 若恢复时有 durable Interaction response，先对账 pending request，再投递一次；不存在对应 request 时不得猜测已消费。

## 9. 证据

每个 Run 至少保留 Mission/commands、原始 SSE、归一化事件、conversation/messages、diff 或 artifact 引用、status 对账、Interaction 请求/响应、acceptance report 和 result。证据写入有界；当前 Run 的最终 result 和 acceptance report 不因轮换删除。

`executionProfile` 只选择 Node 本机配置的受信验收别名。Profile 提供固定参数数组、超时、workspace 内 required artifacts 和输出上限；Node 使用 `ProcessBuilder(List<String>)` 直接执行，不经过 shell，也不接收 Service 下发的任意命令。exit 0 且 required artifacts 全部存在才是 PASSED；非零或缺产物为 FAILED，未配置、启动失败或超时为 UNKNOWN。完整 stdout/stderr 和 `acceptance-report.json` 只留 Node。

## 10. 测试 Gate

Node 单测/集成测试使用临时 fake OpenCode HTTP/SSE server，覆盖：health/version、Session 创建和一次 prompt、busy/retry/idle、SSE 丢失后 status/messages 补偿、permission/question、abort、重启绑定、404/409/5xx、畸形 JSON/SSE、超时和验收与 idle 分离。真实 WS Gate 必须使用显式动态 endpoint，不运行生产副作用；未验证的 Midea server discovery 继续作为阻塞，不得伪造可用性。
