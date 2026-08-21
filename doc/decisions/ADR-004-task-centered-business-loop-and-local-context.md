---
status: authoritative
authority: DF-0.5-business-loop
source_ref: ADR-004
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# ADR-004：以 Task 为业务权威，本机上下文由 Node 解析

## 背景

v0.3 已证明 `Service -> Node -> WS` 可以运行真实开发任务，但产品入口仍是“创建 WorkItem 并立即启动”。用户看不到一个任务从草稿、准备、执行、审查到归档的业务生命周期，也无法在 Web 完成增删查改、验收、退回和历史追溯。与此同时，完整开发资料位于内网工作电脑，WS 才能读取；把资料搬入 Service 既没有必要，也违反既有数据边界。

`xlf/` 只是临时记录方式，不应成为新系统的长期同步依赖。直接一次性建设完整项目管理平台又会推迟真实业务投入，并增加未经使用验证的模型成本。

## 决定

1. Workbench Service 成为 Requirement/Task、计划、派发意图、验收和历史的长期权威；Task 是首个主要产品对象。
2. 详细文档、源码和执行证据继续留在工作电脑。Service 只保存 `targetNodeId`、不泄漏路径的 `workspaceRef/contextRef` 和短控制摘要；Node 本地映射并校验真实路径，再交给 WS。
3. Task 创建与执行解耦。只有用户对 `READY` Task 执行显式“开始”，才创建 WorkItem、不可变 Mission 和 Run。
4. Task、Run、Acceptance 使用独立状态轴。Run 的可信 `completed` 只把 Task 推到 `REVIEW`；用户接受后 Task 才能 `DONE`。
5. 一个 Task 可以有多个 WorkItem/Mission/Run。重试和退回必须追加新历史，不能修改旧 Mission 或覆盖旧 Run/evidence。
6. 从第一阶段建立 `version + expectedVersion` 乐观锁、`Idempotency-Key`、actor/reason 和 append-only TaskEvent。后续 Kanban、Approval、Change/PR/Evidence 和搜索在这条骨架上扩展。
7. 第一阶段使用 Web BFF 的短轮询提供接近实时的状态。浏览器只访问同源 Web，Service credential 留在服务端；不改变 Node 只出站和 Service 不回连 Node的边界。
8. xlf 只做只读发现和用户显式导入；导入后 Workbench 是任务状态权威，不建设长期双向同步。
9. 采用完整蓝图下的阶段纵切。P1 必须闭合一个可日常低频使用的真实任务；未被真实场景需要的对象不提前做成大平台。

## 结果

用户可以先用一条完整任务链路管理真实工作，随后在相同 ID、状态轴和事件历史上增加规划、审批与搜索。代价是 Service 需要新增 Task 聚合和写入型 Web BFF，Node 需要稳定的本机 ContextRegistry/安全 evidence 索引；同时必须处理版本冲突、幂等、审计和旧 v2 WorkItem 投影的关联。

本决定不改变 ADR-003 的 Node→WS 原生路径，也不允许 Service/Web 保存绝对路径、Session、原始事件、diff、日志或完整文档。

## 被拒绝方案

- **先做完整项目管理平台再投入使用**：首个业务价值太晚，模型未经真实使用验证；
- **只给现有 WorkItem 页面加按钮**：虽然短期闭环，但 Task/Run/验收继续混合，后续改造成本高；
- **继续让 xlf 做长期 source of truth 并双向同步**：冲突、去重和状态漂移会形成第二套业务状态机；
- **把本机文档上传 Service**：扩大敏感面，且破坏本地执行边界；
- **让浏览器直连 Service 或 Node 获取实时状态**：泄漏 credential 或要求内网入站连接；
- **把 WS idle/Run completed 自动解释为 Task done**：混淆执行与业务验收。
