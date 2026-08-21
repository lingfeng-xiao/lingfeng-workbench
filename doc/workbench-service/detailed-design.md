---
status: authoritative
authority: DF-0.5-business-loop
source_ref: service-design-s3-task-p1
owner: workbench-service
superseded_by: null
last_verified: 2026-08-21
---

# workbench-service 详细设计 S3

## 0. v0.5 P1 增量

Service 新增独立版本的 `/api/tasks/v1`，不改变 Client API v2“创建 WorkItem 即执行”的语义。Task 是业务对象；WorkItem/Mission/Run 是执行对象；Acceptance 是独立人工轴。Task create/edit/mark-ready 不创建执行记录，READY 的显式 start 才在单事务内创建新 WorkItem/Mission/Run/START_RUN 和关联 TaskEvent。每次重试追加新 revision/Run，不覆盖历史。

所有 mutation 要求 Idempotency-Key、actor、reason；create 以外还要求 expectedVersion。TaskEvent 与变更同事务 append-only。successful Run 只投影为 `REVIEW/PENDING`，失败或不确定返回 READY 并产生 attention；只有显式 accept 且 delivery summary、commit SHA、HTTPS PR URL 齐备时进入 DONE。归档只允许 DONE/CANCELLED，可恢复且不物理删除。

Task 产品字段只保存 workspaceRef/contextRef 安全 alias 和短控制摘要。`mission_context_refs` 只为 Node Protocol 命令重建 alias，不保存解析路径。Task 查询计算 Node 在线性、lastObservedAt 和 stale；旧 v2 WorkItem 没有 Task 关联时不受投影影响。

以下 S2 小节继续描述 Client/Node API v2 执行骨架；其中“创建任务”仅指旧 v2 的 WorkItem 创建，不代表 v0.5 Task 产品语义。

## 1. 模块职责

Service 是全局控制面：接受可信客户端创建任务和解决 Interaction，向 Node 提供持久化 pull 命令，接收幂等控制事件，汇总 WorkItem/Mission/Run 状态，并为 Hermes 维护通知 outbox。它不调用 Runtime、不读取工作电脑、不保存完整执行内容。

## 2. 内部边界

```text
api.client        Client API v2、scope 和请求校验
api.node          Node Protocol v2、nodeId 绑定和 ACK
application       事务用例与状态编排
domain            聚合规则、状态转换、digest 和通知策略
persistence       Spring JDBC repository、Liquibase、SQLite
security          credential 认证、scope 和审计主体
projection        Web/Hermes 使用的短查询模型
```

禁止在 controller 中直接写 SQL 或决定状态，禁止 repository 猜测业务转换，禁止引入 JPA、共享 DTO 或其它模块依赖。

## 3. 领域对象

### WorkItem

上层工作聚合。S2 仍由创建请求原子创建 WorkItem 和首个 Mission，不提供编辑、追加 Mission、历史导入或跨 Node 迁移。状态由 Mission 汇总：`open / in_progress / completed / attention_required / cancelled`。

### Mission

不可静默修改的执行合同。digest 覆盖 objective、acceptance、授权副作用、目标 Node、workspaceRef、runtimeKind 和 executionProfile。任何合同变化必须创建新 revision；S2 不开放该能力。

### Run

Mission 的一次执行。Service 状态为：

```text
assigned -> running -> waiting_interaction -> running
                    -> cancelling -> cancelled/interrupted
                    -> completed/failed/interrupted/uncertain
```

只有 `SUCCEEDED + PASSED + digest 匹配` 可 completed。`UNKNOWN` 必须 uncertain。

汇总规则固定为：Run `assigned/running` 使 Mission/WorkItem 为进行中；Run `waiting_interaction` 使 Mission 等待输入、WorkItem 为 `attention_required`；响应 consumed 后恢复 `in_progress`；Run `failed/interrupted/uncertain` 使 WorkItem 为 `attention_required`；只有 Mission 可信完成才使单 Mission 的 WorkItem 为 `completed`。Node 离线只改变 Node 投影，不直接改变 Run。

### Interaction

精确绑定 `interactionId/runId/checkpointId/missionDigest/targetNodeId`。状态：

```text
pending -> resolved -> delivered -> consumed
pending/resolved -> expired | cancelled
```

Service 保存短 prompt、allowedDecisions、短响应和审计主体，不保存完整 Agent 对话。

### Node

只保存身份、显示名、能力、最后心跳、当前 Run 投影和在线状态。在线/离线是查询时投影，不作为历史真实执行结果。

### ControlCommand

Service 到 Node 的内部 durable record，不是新业务聚合。包含 commandId、目标 Node、Run 绑定、类型、严格 payload、创建时间和 ACK 时间。只支持 `START_RUN / PROVIDE_INTERACTION_RESPONSE / CANCEL_RUN`。

### NotificationDelivery

由领域状态转换同事务创建的投递记录，不拥有业务判断。保存逻辑目标、短消息、租约、attempt、delivery status 和 Hermes 回执；不保存微信凭证或联系人详情。

## 4. 关键事务

### 创建任务

同一事务内：校验幂等键和请求 digest → 创建 WorkItem → 创建 Mission/revision/digest → 创建 Run → 创建 START_RUN command → 写短审计。事务失败不得留下半个任务。

### 接收 Interaction

同一事务内：校验 Node、Run、digest 和当前状态 → 幂等写 Interaction → Run/Mission/WorkItem 进入 waiting/attention 投影 → 创建 `INTERACTION_REQUIRED` notification → 写时间线 → ACK Node 事件。

### 解决 Interaction

同一事务内：校验 credential scope、幂等键、五元绑定、状态和允许决策 → 保存短响应 → Interaction 进入 resolved → 创建 PROVIDE_INTERACTION_RESPONSE command → 写时间线。重复相同请求返回原结果；不同响应冲突。

### Node 确认响应

`COMMAND_STORED` 只结束 command 重投；若是 Interaction 响应命令，同时把 Interaction 置 delivered。`INTERACTION_RESPONSE_CONSUMED` 才置 consumed，并允许 Run 从 waiting_interaction 回到 running。

### 接收终态

同一事务内：校验事件幂等、绑定、Run 状态和 digest → 应用可信终态规则 → 汇总 Mission/WorkItem → 创建完成、失败或 uncertain 通知 → 写时间线 → ACK。迟到或冲突终态不得覆盖已接受终态。

## 5. SQLite 模型

在现有表之外增加或扩展：

```text
control_command
interaction
notification_delivery
node_event_dedup
client_idempotency
timeline_event
```

所有外键开启；写事务使用单写连接、busy timeout、WAL 和 FULL synchronous。Liquibase 只新增前向迁移，不修改已经发布的 migration。升级前必须备份 SQLite，回滚依赖恢复文件而不是反向 migration。

建议唯一约束：

- client credential + idempotency key；
- nodeId + messageId；
- commandId；
- runId + checkpointId；
- eventId + notificationType + targetAlias；
- notificationId + delivery report id。

## 6. API 与凭证

- v2 按 `contracts/control-loop-v2.md` 作为唯一 API/协议；
- 旧路径不重定向、不兼容解析，也不共享 DTO；
- Hermes scope 拆分为 create、resolve、notification pull/report 和 read；
- Sites credential 只有 read；
- Node credential 绑定唯一 nodeId，只能 poll 自己的命令和上报自己的事件；
- 浏览器永不持有任何 Service credential。

所有写 API 均要求明确 request/message ID；所有响应和异常不回显 token、上游 body、Runtime 字段或 SQLite 细节。

## 7. 通知租约

Hermes poll 最多租用一个 notification。租约过期且无成功回报时可重投；`DELIVERED` 为终态；`FAILED` 增加 attempt，达到配置上限进入 `dead_letter`。Service 不声称微信实际被用户阅读。

Node 离线告警只在“Node 超过阈值未心跳且存在活动 Run”时创建，使用稳定 dedup key 和冷却窗口，避免每次查询产生通知。

## 8. 查询投影

Web 和 Hermes 可读取：WorkItem/Mission/Run 短时间线、phaseCode、progressSummary、lastSyncedAt、Interaction 状态、Node 在线投影和重要 notification delivery 状态。查询不返回 command payload、resolvedBy 内部标识、workspaceRef、missionDigest 或其它不需要显示的敏感字段。

## 9. 失败策略

- SQLite 写失败：整个用例失败，不提前 ACK；
- 重复同 payload：返回幂等成功；
- 同 ID 不同 payload：409；
- Node/digest/state 不匹配：409 或协议拒绝；
- Hermes 暂时离线：notification 保留并重投，不影响业务状态；
- Node 暂时离线：command 保留，Run 不伪造失败；
- 未知终态：Run uncertain，必须人工关注。

## 10. 验收边界

Service 独立测试必须覆盖迁移和重启、所有关键事务、并发 poll、命令重投、事件幂等、Interaction 生命周期、通知租约、scope、非法转换和敏感数据扫描。Service 测试不启动真实 Runtime、微信或 Sites。
