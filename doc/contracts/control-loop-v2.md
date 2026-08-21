---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: control-loop-v2-current
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# Control Loop v2 跨模块语义合同

## 1. 通用信封

所有 Node 命令和事件必须包含：

```text
protocolVersion = 2.0
messageId
nodeId
sentAt
```

与 Run 相关的消息还必须包含：

```text
workItemId
missionId
runId
missionDigest
```

所有 ID、digest、目标 Node、协议版本和消息大小在处理前校验。未知字段、未知枚举、同 ID 不同 payload、超出 64 KiB 或短文本超过 800 字符均 fail closed。

## 2. Service 到 Node

Service 不发送网络请求，只把命令持久化，由 Node 调用 `/api/node/v2/poll` 拉取。首轮命令类型：

### START_RUN

```text
commandId, workItemId, missionId, runId
missionRevision, missionDigest
objective, acceptanceSummary, authorizedSideEffectsSummary
targetNodeId, workspaceRef, runtimeKind, executionProfile
contextRefs?（v0.5 可选安全别名数组；旧 v2 命令省略时语义不变）
```

### PROVIDE_INTERACTION_RESPONSE

```text
commandId, workItemId, missionId, runId, missionDigest
interactionId, checkpointId, targetNodeId
decision, responseSummary, resolvedAt
```

### CANCEL_RUN

```text
commandId, workItemId, missionId, runId, missionDigest
targetNodeId, reasonSummary
```

命令持续重投，直到 Service 接受匹配的 `COMMAND_STORED`。Node 只有在命令和 payload digest 已持久化到本地数据库后才能发送该 ACK。相同命令重放不得产生第二次 Runtime 副作用；同 `commandId` 不同 payload 必须进入本地安全故障并上报冲突。

## 3. Node 到 Service

Node 事件先进入本地 outbox，Service 返回包含 `requestMessageId` 和 `duplicate` 的 ACK 后才能删除。首轮事件：

- `COMMAND_STORED`：命令已安全落盘；
- `RUN_STARTED`：Agent Session 已打开或恢复，包含 `resumable`；
- `PHASE_CHANGED`：稳定 `phaseCode` 和短摘要；
- `PROGRESS_UPDATED`：短进度；
- `INTERACTION_REQUESTED`：精确绑定、允许决策和短提示；
- `INTERACTION_RESPONSE_CONSUMED`：同一 Session 已接受响应，对应 Interaction 进入 consumed；
- `RUN_TERMINAL`：`runtimeOutcome`、`acceptanceStatus`、`resultSummary`、`resumable=false`。

高频 tool call、文件变化、token、stdout/stderr 和 Runtime message 事件不进入 Node Protocol。

## 4. Interaction 状态

```text
pending -> resolved -> delivered -> consumed
       \-> expired
       \-> cancelled
```

- `pending`：Node 请求且 Service 已持久化；
- `resolved`：可信客户端已提交合法响应；
- `delivered`：Service 已接受响应命令对应的 `COMMAND_STORED`；
- `consumed`：同一 Agent Session 已接受响应；
- `expired/cancelled`：终态，不能再次 resolve。

resolve 使用 `Idempotency-Key`，请求必须回传 `interactionId + runId + checkpointId + missionDigest`。同键同请求返回原结果；同键不同请求、已被不同响应解决或绑定不匹配返回冲突。

## 5. Hermes Client API v2

Client API v2 提供创建、读取、Interaction 和 Notification 能力：

```text
POST /api/client/v2/interactions/{interactionId}/resolution
POST /api/client/v2/notifications/poll
POST /api/client/v2/notifications/{notificationId}/delivery-events
```

Hermes credential 的写 scope 分离为：

```text
work-items:create
interactions:resolve
notifications:pull
notifications:report
```

Web BFF 使用分离的 Task read/write credential；浏览器不持有任何 Service credential。Client API v2 的 Sites 兼容读取 credential 仍只有 read scope。

Notification poll 最多返回一项，包含：

```text
notificationId, notificationType, targetAlias=owner
workItemId, missionId?, runId?, interactionId?
title, messageSummary, createdAt, attempt
```

Hermes 以 `notificationId` 去重，并回报 `DELIVERED` 或 `FAILED`。Service 的投递状态为 `pending / leased / delivered / dead_letter`；租约超时可重投，失败达到有界次数后进入 dead letter。Service 不保存微信用户 ID、微信 token 或完整回复文本。

## 6. Node 到 Runtime 的本地会话边界

具体决定见 ADR-003。Service→Node 的 HTTP 字段和事件集合不包含 OpenCode DTO、Session ID 或原始事件；Node 不得用固定 Turn 或模型输出 terminal 文本实现这些投影。

该合同不通过 HTTP，不进入 OpenAPI。Node SPI 提供异步命令：

```text
probe/capabilityGate
openSession
submitMissionPromptAsync
subscribeEvents
reconcileStatusMessagesAndPendingInteractions
provideInteractionResponse
reattachSession
abort
closeSession
```

Adapter 产生归一化事件：

```text
SessionOpened
MissionAccepted
RuntimeStatusChanged
PhaseChanged
ProgressUpdated
ArtifactChanged
InteractionRequested
RuntimeIdle
RuntimeWarning
SessionFailed
SessionClosed
```

SPI 必须异步，不得阻塞 ServiceConnectionLoop。`ArtifactChanged`、RuntimeWarning 详情和 message 内容默认只落本地。Adapter 私有保存 Runtime Session/handle、server identity、原始消息和事件；验收状态由独立组件产生，不由 Adapter 解释 assistant 文本。

## 7. 状态映射

Service Run 状态：

```text
assigned, running, waiting_interaction, cancelling,
completed, failed, interrupted, uncertain, cancelled
```

Node 本地 Run 状态：

```text
received, preparing, opening_session, running,
waiting_interaction, resuming, cancelling,
completed, failed, interrupted, uncertain
```

Agent Session 本地状态：

```text
opening, idle, busy, retry, waiting_interaction,
error, aborted, closing, closed, lost
```

Service 不保存 Session 状态；只保存 Run 和 `resumable` 投影。

## 8. 顺序与竞争

- Node 为每个 Run 分配单调递增 `localSequence`，将命令、Runtime 事件和定时器放入同一串行队列；
- 第一个本地持久化的合法终态关闭 Run，之后事件不改变结果；
- CANCEL_RUN 先落盘时，Node 不再提交新 prompt/message，并调用原生 Session abort；
- 可信 PASSED 终态先落盘时，之后的取消只作为迟到命令 ACK，不覆盖完成；
- Service 仍依据自身状态机和幂等记录做最终校验，拒绝不可能的转换。

## 9. OpenAPI 编码要求

`task-api-v1.openapi.yaml`、`client-api-v2.openapi.yaml` 和 `node-protocol-v2.openapi.yaml` 是当前 HTTP 合同。OpenAPI 必须表达上述必填字段、严格对象、枚举、scope、大小边界、冲突响应和示例 fixtures；语义冲突时以本文件和冻结 ADR 为准，先修订冻结版本再继续实现。
