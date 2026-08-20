---
status: authoritative
authority: DF-0.3-control-loop
source_ref: node-design-n2
owner: workbench-node
superseded_by: null
last_verified: 2026-08-20
---

# workbench-node 详细设计 N2

## 1. 模块职责

Node 是工作电脑上的本机 Agent 会话控制器。它可靠地接收 Service 命令、管理一个活动 Run 和一个 Agent Session、保存完整本地证据，并只向 Service 投影短状态。Agent Runtime 才执行源码分析、修改、构建和测试。

## 2. 内部组件

```text
connection
  ServiceConnectionLoop, HTTPS client, proxy/TLS, backoff

orchestration
  CommandInbox, RunSupervisor, Run event queue, timers

session
  AgentSessionController, TurnController, checkpoint binding

runtime
  runtime-neutral async SPI and normalized events

runtime/ws
  WS-specific process/session protocol and terminal interpreter

localstate
  SQLite indexes, command inbox, event log, outbox, evidence files

config
  node identity, service endpoint, proxy, workspace mapping, profiles
```

`ServiceConnectionLoop` 不得调用阻塞的 Runtime 方法；`RunSupervisor` 不得执行 HTTP；Adapter 不得直接更新 Service 或全局业务状态。

## 3. 并发模型

- 一个网络调度器持续 hello、heartbeat、poll 和 flush outbox；
- 一个 RunSupervisor 串行处理当前 Run 的命令、Runtime 事件、超时和取消；
- Runtime I/O 使用独立受控 executor；
- 所有跨线程输入先转换为带 `localSequence` 的本地事件并持久化；
- 首轮 `maxActiveRuns=1`，新的 START_RUN 在已有活动 Run 时不 ACK 为可执行，返回有界 busy 状态并等待 Service 重投。

Agent 运行数小时不会阻塞心跳、取消命令或 Interaction 响应拉取。

## 4. 本地状态

`node.db` 至少记录：

```text
received_command(commandId, payloadDigest, state)
local_run(runId, missionDigest, state, nextSequence)
agent_session(runId, encryptedOrProtectedHandleRef, state, resumable)
turn(turnLocalId, runId, state)
interaction_binding(interactionId, checkpointId, responseState)
local_event(runId, localSequence, eventType, payloadRef)
outbox(messageId, payloadDigest, state)
```

每个 Run 目录保留现有五类证据，并扩展：

```text
mission.json
control-commands.ndjson
normalized-events.ndjson
runtime-events.ndjson
runtime-stderr.log
conversation.ndjson
checkpoints/
result.md
```

Session handle、完整响应和绝对路径只写本地。日志应有大小/轮换边界，但不得因轮换删除当前 Run 的最终 result 和 Mission 快照。

## 5. 命令处理

### START_RUN

校验 nodeId、runtimeKind、workspaceRef 映射和 missionDigest → 本地事务写 command、Mission 快照和 Run → 发送 COMMAND_STORED → Supervisor 准备工作区 → `openSession` → `submitTurn`。重复 command 只返回相同 ACK，不再次打开 Session。

### PROVIDE_INTERACTION_RESPONSE

校验五元绑定、当前 Run waiting、Session/checkpoint 存在 → 本地事务保存完整响应和 command → 发送 COMMAND_STORED → 调用同一 Session 的 `provideInteractionResponse` → Adapter 接受后发送 INTERACTION_RESPONSE_CONSUMED。任一步失败都保留响应供恢复，不提前消费。

### CANCEL_RUN

先持久化取消命令，再由 Supervisor 停止提交新 Turn并调用 Adapter cancel。重复 cancel 安全。取消与终态按 `localSequence` 决定先后，后到事件不能覆盖已持久化终态。

## 6. Runtime SPI

SPI 是异步会话协议：

```text
probe() -> CompletionStage<RuntimeProbe>
capabilities() -> RuntimeCapabilities
openSession(context, sink) -> CompletionStage<LocalSessionHandle>
submitTurn(session, turn, sink) -> CompletionStage<Void>
provideInteractionResponse(session, binding, response, sink)
requestCheckpoint(session, sink)
pause(session, sink)
resume(session, recoveryContext, sink)
cancel(session, reason, sink)
inspect(session) -> CompletionStage<SessionInspection>
closeSession(session, sink)
```

具体 Java 签名可以模块内调整，但必须保留异步、会话、Turn、Interaction 绑定和显式 handle 语义。事件集合以 `contracts/control-loop-v2.md` 为准。

Adapter 负责 Runtime 专用协议转换、Session/handle 和结构化终态解析；Node core 不出现 `ws` 命令行参数、模型名或专用 JSON 字段。

## 7. Interaction

`InteractionRequested` 不取消 Runtime。Supervisor：

1. 保存完整本地 prompt、allowed decisions、checkpoint 和 Session handle；
2. Run 进入 `waiting_interaction`；
3. 上报不超过 800 字符的 promptSummary 和精确绑定；
4. 保持 Session，必要时由 Adapter pause/checkpoint；
5. 重启后仍以原 Mission、handle 和 checkpoint 恢复；
6. 只有正确响应被 Adapter 接受后才回到 running。

Runtime 自己提出的普通澄清与需要外部审批的 Interaction 必须由 Adapter 显式区分；Adapter 不确定时一律上升为 Interaction，不猜测答案。

## 8. 重启与断网

启动恢复顺序：打开本地数据库 → 校验未终结 Run → 重放未 ACK outbox → inspect 原 Session → 能证明 handle 且 capability 支持时 resume → 否则进入 uncertain。禁止自动新建替代 Session。

Service 离线时：已运行 Runtime 继续；本地事件继续落盘；等待 Interaction 的 Run 保持等待；无新任务可领取。连接恢复后先 flush outbox，再 heartbeat/poll，保证 Service 先看到旧事件。

Runtime 进程丢失时：inspect/resume 成功则继续；无法恢复时记录 `SessionFailed` 并上报 `FAILED` 或 `UNKNOWN`，不得根据退出码猜测 PASSED。

## 9. 企业网络

Node 配置支持：

- HTTPS Service URL；
- 显式 HTTP/HTTPS proxy URI；
- 可选 proxy authentication 的外部 secret 引用；
- 自定义 truststore；
- connect/request timeout；
- poll、heartbeat 和 backoff 间隔。

启动预检分别报告 DNS、proxy、TLS、401/403、协议版本和 Runtime probe；日志不得输出 bearer credential 或 proxy password。Node 不监听业务端口。

## 10. WS Adapter

WS 是首个 Adapter，不是 core 模型。fake session Runtime 已验证多 Turn、Interaction 和恢复；WS Adapter 可在当前 Node 进程中使用真实观测到的 Session ID 和 `--session` 连续提交多个 Turn。真实 WS 只有包含匹配 digest 的结构化终态且 `SUCCEEDED/PASSED` 才产生通过；正常退出、无输出或普通文本均为 UNKNOWN。

当前没有证据证明 WS Session 可跨 Node 进程恢复，也没有 durable Interaction 能力，因此 Adapter 不声明 `resume`/`interaction` capability，并在相关调用中 fail closed。真实文件任务必须另过工作区和授权 Gate。

## 11. 验收边界

Node 独立测试覆盖 fake Service + fake Runtime、多 Turn、网络与 Runtime 并行、命令落盘 ACK、outbox 重放、重复命令、Interaction 恢复、取消竞争、重启、Session 丢失、企业代理配置、可信终态和本地/Service 数据泄漏。Node 测试不需要真实 Service、Sites 或微信。
