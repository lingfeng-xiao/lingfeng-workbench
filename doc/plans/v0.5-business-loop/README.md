---
status: authoritative
authority: DF-0.5-business-loop
source_ref: plan-v0.5-business-loop
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# v0.5 第一阶段实施计划：先闭合一个耐久 Task

## 1. 交付边界

本计划只实现 DF-0.5 的 P1：用户能在 Web 创建并管理一个 Task，显式交给现有 `Service -> Node -> WS` 控制环，看到持续短进度，在执行完成后验收或退回，并能归档和追溯。

P1 不实现 Requirement UI、拖拽 Kanban、复杂 Queue、通用 Interaction/Approval 工作台、全文检索、xlf 批量迁移、真实消息、部署、Sites 发布或旧资产清理。

## 2. 开工前保护 Gate

原始工作区当前包含未提交的 ADR-003/native OpenCode Runtime 重建。实施者必须先：

1. 记录 `git status` 和受影响文件，禁止 reset、checkout、clean 或覆盖现有修改；
2. 阅读 DF-0.5、ADR-004、ADR-003、current-state、control-loop-v2 和三个模块 detailed-design；
3. 运行与当前工作树相称的现有单测，区分基线失败与新增失败；
4. 先确认 native OpenCode 工作的实际接口和完成状态，再决定最小集成点；
5. 如现有未提交工作与 P1 修改同一代码区域，优先做小步兼容修改并记录归属，不擅自回滚另一项工作。

## 3. 最小数据迁移

新增 Liquibase 变更集，至少包含：

- `task`：ID、title、objective、acceptanceSummary、sideEffectSummary、priority、targetNodeId、workspaceRef、contextRefs 安全结构、businessStatus、acceptanceStatus、attentionState、version、created/updated/archived 元数据；
- `task_event`：event ID、task ID、顺序、type、短 summary、actor、source、关联 WorkItem/Mission/Run、occurredAt；
- 现有 WorkItem 到 Task 的可空关联或独立关联表；
- 最小交付字段：deliverySummary、commitSha、prUrl，后续可迁入 Change/PR 聚合。

旧 WorkItem 不伪造 Task，不回填虚假业务状态。它们继续通过 v2 页面查询；只有用户显式“纳入任务池”时才创建带 `legacyWorkItemId` 的 Task，并记录事件。迁移必须可在现有 SQLite 上前向执行，且不删除、重命名或重写旧表和旧记录。

## 4. 实施纵切

### Slice A：Task 聚合和写入合同

- 新增 Task 创建、列表、详情、编辑、READY、开始、验收、退回、取消、归档和恢复能力；P1 不做物理删除；
- 写操作统一要求 Idempotency-Key、actor/reason；对已有 Task 的 mutation 另要求 expectedVersion；状态转换由服务端校验；
- 每次 mutation 与 TaskEvent 原子提交；
- 为 Task 到现有 WorkItem/Mission/Run 建立关联，不复制 Node 状态机；
- 客户端产品合同使用新版本命名空间，旧 Client API v2 和 Node Protocol v2 保持兼容。

Gate：Service 集成测试覆盖合法转换、非法转换、并发冲突、幂等重放/冲突、重试保留旧 Run、重启持久化和敏感字段拒绝。

### Slice B：显式开始接入现有控制环

- READY Task 的 start 在一个事务内冻结 Task version，创建 WorkItem/Mission/Run/START_RUN command 和关联事件；
- 复用 `workspaceRef`，增加 `contextRef` 时仅传安全别名；Node ContextRegistry 本机解析并校验允许根；
- 重复 start 不产生第二个 Run；活动 Run 时拒绝再次 start；
- Node 的现有 progress/terminal 事件投影为 TaskEvent 和 attention；
- successful Run 只触发 Task `REVIEW + PENDING`，失败/不确定产生 attention，不自动 DONE。

Gate：fake Runtime 组合 E2E 证明一次显式开始只产生一个 Session/Run，至少两次进度可见，终态后 Task 为 REVIEW；Service 重启后关联与 Timeline 不变。

### Slice C：可操作的 Web 任务池

- 使用 Web 同源 BFF 增加任务池、Task 表单、Task 详情和待我处理；
- BFF 服务端持有按读写范围分离的 Service credential，浏览器不接触 credential；
- 所有表单携带 CSRF 防护、Idempotency-Key 和 expectedVersion；冲突页面显示“数据已更新”并允许重新加载；
- Task 详情 3–5 秒条件轮询，显示业务/执行/验收三轴、Node 在线性、lastObservedAt、stale、历次 Run 和 Timeline；
- 操作按钮由服务端返回的 allowedActions 驱动，不能只靠前端隐藏非法动作。

Gate：Web 测试覆盖创建、编辑、刷新幂等、开始、自动刷新、验收、退回、归档/恢复、版本冲突、Service 不可用和响应脱敏。

### Slice D：真实低风险业务验收

从 Web 创建一个授权范围明确的小型开发 Task，引用工作电脑上已登记的 workspace/context，完成真实 WS 执行。人工审查 commit/PR/交付摘要后验收并归档。

Gate：DF-0.5 第 13 节全部成立；原始 Session、绝对路径、正文、diff、日志和 secret 只存在 Node 本地证据；所有失败样本保留，不能用人工改库补成成功。

## 5. API 与 UI 约束

- 不在现有 v2 `POST /work-items` 上叠加“草稿 Task”语义；新产品合同单独版本化；
- 不允许通用 `PATCH status`，使用 `mark-ready/start/accept/request-changes/cancel/archive/restore` 等明确动作；
- Service 返回 `allowedActions`、version、业务/执行/验收状态和安全 attention，Web 不自行推演状态机；
- Timeline 使用游标/顺序增量读取，首版可限制最近事件，但必须能分页查看全部历史；
- 轮询是 P1 的正式方案，不以 SSE/WebSocket 作为上线前置条件。

## 6. 风险与停止条件

- **脏工作区冲突**：触碰 native OpenCode 修改前先审计；不能证明兼容时停止该切片并报告具体文件；
- **状态混合**：任何让 Run completed 直接写 Task DONE 的实现不得合入；
- **路径泄漏**：Service DB/API/Web/浏览器出现绝对路径或 Session ID 立即停止；
- **重复副作用**：重复 start、刷新或网络重试产生第二个 Run/Session 立即停止；
- **伪实时**：lastObservedAt 过期必须标 stale，不能重复展示旧 progress 冒充活动；
- **范围膨胀**：Kanban、通用审批和搜索不得挤占首个真实闭环；发现需求记录到后续阶段。

## 7. 完成定义

P1 完成不是“接口存在”，而是一个用户不离开 Web 就能控制 Task 生命周期，并由工作电脑上的 WS 使用本机上下文完成一次真实开发：

```text
create -> edit -> ready -> start -> observe -> review -> accept/reject -> done -> archive
```

代码、测试、迁移、合同和文档必须一致；根 reactor、Service/Node/Web 测试、fake 组合 E2E 和一个真实低风险 E2E 均有可重复证据。开发完成不自动授权 commit、push、部署、Node 安装、真实消息或旧数据删除。
