---
status: authoritative
authority: DF-0.5-business-loop
source_ref: product-review-2026-08-21
owner: architecture
supersedes:
  - DF-0.3 Web read-only product limitation
  - DF-0.3 create-WorkItem-means-start product flow
  - SPM/xlf as ongoing task source of truth
superseded_by: null
implementation_status: frozen-candidate
last_verified: 2026-08-21
---

# DF-0.5：以 Task 为中心的最小耐久业务闭环

## 1. 冻结结论

Workbench 从“能把一个 WorkItem 交给 WS”演进为日常需求与任务控制台。第一阶段不追求完整项目管理平台，而是交付一条可以立即低频使用、后续无需推倒重来的纵向闭环：

```text
用户创建 Task -> 补齐本机上下文引用 -> READY -> 显式开始
  -> Service 创建 WorkItem / Mission / Run -> Node 出站领取 -> WS 执行
  -> Web 持续显示短进度和新鲜度 -> Run 得到可信终态 -> Task 进入 REVIEW
  -> 用户验收或退回 -> DONE -> ARCHIVED
```

以下三类事实必须分开：

- **业务事实**由 Workbench 的 Requirement/Task 保存；
- **执行事实**由 WorkItem/Mission/Run 保存，WS observation 是执行状态的主要来源；
- **验收事实**由人的接受/退回和可核验交付摘要保存。

因此，`Run=completed` 只表示本次执行已可信结束，不再等价于 `Task=DONE`。

## 2. 用户能够看见和控制什么

第一阶段上线后，用户至少可以：

1. 在 Web 新建、查看、编辑、查询、归档和恢复 Task；P1 的“删除”统一采用可恢复归档，不提供物理删除；
2. 为 Task 选择目标 Node、本机 `workspaceRef` 和一个或多个安全的 `contextRef`，但 Web 和 Service 看不到绝对路径或文档正文；
3. 把 Task 从 `DRAFT` 明确推进到 `READY`，再由一次显式操作开始执行；创建 Task 本身绝不启动 WS；
4. 在 Task 详情中看到当前执行阶段、短进度、最后观测时间、Node 在线性、是否陈旧以及历次 Run；
5. 在成功 Run 后查看 commit、PR、交付摘要和本地 evidence 是否可用的安全索引，决定“验收”或“退回修改”；
6. 对失败、超时、不确定或离线任务执行重试、取消、退回或继续等待，旧 Run 和旧证据不被覆盖；
7. 从 Timeline 追溯谁在何时创建、修改、开始、执行、等待、验收、退回和归档。

完整蓝图还包括 Requirement 池、排队与 Kanban、Interaction/Approval、Change/PR/Evidence、历史搜索；它们按真实使用逐段加入，共享第一阶段的 Task、版本、事件和执行关联，不另造第二套状态机。

## 3. 权威关系和安全边界

```text
Workbench Web（用户操作与可见投影）
        |
        | 同源 BFF；浏览器无 Service credential
        v
Workbench Service（业务与全局控制权威）
        ^
        | Node 主动出站 HTTPS：poll / ACK / event / observation
        |
Workbench Node（本机控制、上下文解析、证据索引）
        |
        | 本机 loopback / 原生 OpenCode API
        v
WS（读取工作电脑上下文并执行；主要执行状态传感器）
```

- Service 是 Requirement、Task、调度意图、全局执行投影、验收和审计的权威来源；
- Node 是本机上下文映射、完整 Session、原始事件、路径、diff、日志、产物和 evidence 的权威来源；
- WS 提供原生 Session/status/event/permission/question，Node 负责落盘、排序、对账、脱敏和出站投影；
- Service 不回连 Node，浏览器不连接 Node，浏览器不持有 Service credential；
- Service/Web 只保存短小、安全、可搜索的控制数据，D1/R2 继续为 `null`；
- `delegate-to-ws`/Bridge 不恢复，唯一执行链路保持 `Service -> Node -> WS`。

## 4. 产品对象和关系

```text
Requirement 1 --- n Task
Task        1 --- n WorkItem 1 --- 1 Mission(revision)
Mission     1 --- n Run
Run         1 --- n Interaction 1 --- 0..1 Approval
Task/Run    1 --- n EvidenceIndex
Task        1 --- n Change 1 --- 0..n PR
Task        1 --- n TaskEvent
```

| 对象 | 用户含义 | 权威位置 | 第一阶段 |
| --- | --- | --- | --- |
| Requirement | 一项业务需求，可拆成多个 Task | Service | 蓝图保留，暂不要求 UI |
| Task | 可规划、可执行、可验收的主要业务对象 | Service | 必做 |
| WorkItem | 一次派发容器，连接 Task 与现有控制环 | Service | 复用并补 `taskId` |
| Mission | 某次派发的不可变执行合同和上下文引用快照 | Service；本地解析结果仅 Node | 复用 |
| Run | Mission 的一次执行尝试；重试新增，不覆盖 | Service 投影 + Node 本地事实 | 复用 |
| Interaction | WS 执行中需要输入或选择的请求 | Service 短投影 + Node 原文 | 后续阶段 |
| Approval | 对 Interaction、风险操作或验收 Gate 的决定 | Service | 人工验收先做，通用审批后做 |
| EvidenceIndex | “有什么证据、是否可取”的安全索引，不是证据正文 | Service 摘要 + Node 实体 | 最小索引先做 |
| Change | commit/变更集及交付摘要 | Service 摘要 | 第一阶段允许人工确认 |
| PR | 外部代码审查链接和短状态 | Service | 第一阶段允许人工确认 |
| TaskEvent | 不可变、可检索的业务审计与 Timeline 事件 | Service | 必做 |

第一阶段只新建支撑真实闭环所需的最小耐久骨架：`Task`、`TaskEvent`、Task 到现有 WorkItem/Run 的关联以及验收/交付短字段。Requirement、通用 Approval 和完整 Change/PR 聚合不应为了“模型完整”而阻塞首个可用闭环。

## 5. 三条状态轴

### 5.1 Task 业务状态

```text
DRAFT -> READY -> IN_PROGRESS -> REVIEW -> DONE -> ARCHIVED
  |        |            |           |        |
  +------> CANCELLED <--+           +-> READY（退回/重做）
```

- `DRAFT`：可编辑，尚不允许派发；
- `READY`：目标 Node、上下文引用、执行目标、验收标准和副作用边界已完整；
- `IN_PROGRESS`：至少一个活动 Run；
- `REVIEW`：成功 Run 已结束，等待用户判断业务是否完成；
- `DONE`：用户已验收；
- `ARCHIVED`：已完成或取消任务从活动视图移出，仍可查询和恢复；
- `CANCELLED`：用户明确终止业务任务，不抹去已有执行历史。

失败、等待输入、Node 离线和 stale 不额外污染 Task 主状态，而以 `attentionState` 呈现：`NONE / WAITING_INPUT / APPROVAL_REQUIRED / RUN_FAILED / RUN_UNCERTAIN / NODE_OFFLINE / STALE`。这让用户同时看到“业务走到哪”和“现在为何需要关注”。

### 5.2 Run 执行状态

继续沿用 v2：

```text
assigned -> running -> waiting_interaction -> running
                    \-> cancelling -> cancelled
running -> completed | failed | interrupted | uncertain
```

Node/WS observation 至少归一化为 `RUN_STARTED / PROGRESS / WAITING / TERMINAL / WARNING`，每条带本地顺序、发生时间和 `lastObservedAt`。Service 只接受单调、幂等且绑定正确的事件；超过新鲜度阈值只投影 `STALE`，不得猜测失败或完成。

### 5.3 验收状态

```text
NOT_REQUESTED -> PENDING -> ACCEPTED
                       \-> CHANGES_REQUESTED -> PENDING（新 Run 后）
```

- Run 可信完成触发 `PENDING` 和 Task `REVIEW`；
- 用户验收后才成为 `ACCEPTED` 和 Task `DONE`；
- 用户退回时必须填写短原因，Task 回到 `READY`，后续显式开始创建新的 WorkItem/Mission/Run；
- Run 失败、不确定、取消或中断不会进入验收，只产生 attention 和可重试动作。

## 6. 本机上下文空间

Workbench 管理需求和任务元数据；详细需求文档、技术分析、源码、SQL、日志、测试资料继续在工作电脑的既有空间维护。

Service 只保存：

- `targetNodeId`；
- 无路径含义的 `workspaceRef` / `contextRef` 标识和用户可读标签；
- 可选的版本提示、用途和短说明；
- 用户在 Mission 中写明的目标、验收标准和授权副作用摘要。

Node 在本机维护 `ContextRegistry`，把这些标识映射到允许的绝对目录/文件，并执行规范化、存在性、允许根、读写能力和敏感性检查。解析成功后，Node 才把本机路径和完整上下文交给 WS。解析失败必须在启动 WS 前 fail closed，并向 Service 只投影安全错误码和短说明。

Task 编辑不会改变已经创建的 Mission。每次显式开始都从指定 Task version 生成新的不可变 Mission revision；因此上下文和验收标准的变更可追溯，也不会静默改变正在运行的任务。

## 7. 第一阶段 Web 信息架构

只建设支持闭环的四个入口：

1. **任务池**：默认显示活动 Task，支持状态、attention、Node、更新时间筛选；首版用列表而非拖拽 Kanban；
2. **新建/编辑 Task**：维护短标题、目标、验收标准、优先级、Node、workspace/context 引用和副作用边界；
3. **Task 详情**：顶部显示业务状态、执行状态、验收状态和可执行动作；中部显示当前/历次 Run；底部显示 Timeline；
4. **待我处理**：首版聚合 REVIEW、失败/不确定、离线/stale；通用 Interaction/Approval 后续接入同一入口。

Timeline 不是原始 WS 日志。它由 TaskEvent 和安全 Run 投影合成，至少显示：创建、编辑、READY、开始、Node 领取、WS 开始、短进度、等待/告警、可信终态、验收、退回、重试、取消、归档和恢复。

## 8. 写操作、并发与审计

- Web 浏览器只调用同源 BFF；BFF 在服务端持有最小权限的 Service credential；
- 所有用户写操作必须携带 `Idempotency-Key`、actor 和 reason；对已有对象的 mutation 还必须携带 `expectedVersion`；
- Service 使用乐观锁拒绝陈旧表单，返回当前 version 和安全冲突摘要；
- 状态变化使用显式 action，不允许客户端任意 PATCH status；
- 每次成功写操作与 TaskEvent 在同一事务提交；
- 活动 Run 期间只能修改不会改变当前 Mission 的展示元数据；目标、上下文、验收和副作用修改只影响下次执行；
- P1 的删除语义是可恢复归档；不提供物理删除，历史和 TaskEvent 始终可追溯。

## 9. 实时更新选择

第一阶段采用 **3–5 秒有条件轮询**：浏览器轮询 Web 同源 BFF，BFF 用服务端凭证读取 Service，并以 version/ETag 只返回变化。页面隐藏时降频，Run 终态后停止高频轮询。

这是当前边界下最快可用的方案：Node 仍只出站，浏览器仍无 Service credential，Service 不需要维持到 Node 的连接。后续任务量和交互密度证明需要时，可由 Web BFF 向浏览器提供 SSE；BFF 再读取 Service 事件游标。WebSocket 只在确有双向低延迟需求时评估，不能让浏览器直连 Service/Node，也不能把 Service credential 下发浏览器。

## 10. xlf 渐进退出

`xlf/` 是临时来源，不再作为长期双向同步对象：

1. **只读发现**：登记来源位置和结构，只生成安全索引/链接，不复制正文；
2. **显式导入**：用户选择条目后创建 Workbench Requirement/Task，保存 `sourceSystem=xlf + sourceId/sourceFingerprint`；
3. **去重**：优先使用稳定 sourceId；缺失时使用规范化相对位置、结构字段和内容指纹，疑似重复必须人工确认；
4. **冲突**：导入后 Workbench 为任务状态权威，xlf 的后续差异只提示，不自动覆盖；
5. **回退**：导入批次可撤销新建且无执行历史的对象；已有执行历史的对象只能归档，映射和审计保留；
6. **退役**：新任务都从 Workbench 创建且用户确认后，停止 xlf 索引。原记录保留为历史，不自动删除。

## 11. 分阶段蓝图

| 阶段 | 用户得到的完整能力 | 明确不做 |
| --- | --- | --- |
| P1 最小耐久闭环 | Task CRUD、显式开始、WS 短进度、Timeline、人工验收/退回、归档 | Kanban 拖拽、通用审批、全文检索 |
| P2 任务池与规划 | Requirement、Backlog、简单 Queue、优先级、Kanban/列表双视图 | 自动调度和复杂依赖图 |
| P3 Attention 与审批 | Interaction、Approval、待办箱、超时/升级、同 Session 恢复 | 浏览器直连 Node |
| P4 交付与证据 | Change/commit/PR/EvidenceIndex、受控本机证据访问、结构化验收 | 上传原始 Session/diff/log 到 Service |
| P5 历史与迁移 | 全局历史搜索、保存视图、xlf 显式批量导入和退役 | 长期双向同步 xlf |

每个阶段都必须能在 Web 中完成一个真实用户场景，并沿用已有 Task ID、version、TaskEvent 和 Run 历史。阶段通过不自动授权部署、真实消息、Node 安装或数据清理。

## 12. Supersede 与继续继承

本冻结已废止旧版本的以下产品限制；这些内容不再是当前要求：

- Web 永久只读；
- 用户只能通过“创建 WorkItem 即启动”使用系统；
- WorkItem 的 `completed` 可代表业务任务完成；
- 无 Task pool/Kanban/历史/审批入口被视为长期目标；
- SPM/xlf 持续作为任务权威来源。

以下架构与安全边界继续有效：

- Service 全局控制面、Node 本机控制面、Service 不回连 Node；
- Node 只出站 HTTPS，浏览器不持有 Service credential、不连接 Node；
- Mission revision/digest、Run/Session 分离、可信 Run terminal projection 与独立 AcceptanceEvaluator；
- Node command 落盘 ACK、outbox、幂等、顺序和 fail closed；
- 完整 Session、原始事件、路径、diff、日志和产物只留 Node；
- ADR-003 的原生 OpenCode Runtime 路径；
- D1/R2 为 `null`，Bridge/delegate-to-ws 废弃；旧 Python 已在独立清理 Gate 下从当前树删除，旧 D1 和其它外部资产仍不自动删除。

## 13. P1 客观验收场景

使用一个真实但低风险的开发 Task，从 Web 完成：创建草稿、编辑、READY、显式开始、Node 解析本机引用、WS 执行、至少两次自动进度刷新、可信 Run 终态、Task 自动进入 REVIEW、填写 commit/PR/交付摘要、用户验收、DONE、归档、搜索并恢复查看完整 Timeline。

验收还必须证明：

- 创建/编辑/READY 不会启动 WS，且一次显式开始只创建一次执行；
- 刷新、重复提交和 Service 重启不产生重复 Task/Run/事件；
- 退回后新 Run 不覆盖旧 Run；
- Node 离线或 observation 过期会显示 stale/attention，而不是伪装成完成；
- Service DB、Web 响应和浏览器存储不含绝对路径、WS Session ID、原始上下文、diff、日志或 secret；
- 原有 v2 控制环和当前 Node→WS 原生化测试继续通过。
