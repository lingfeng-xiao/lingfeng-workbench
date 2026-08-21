---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: workflow-v3
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# 当前业务与执行闭环

## 1. Task 主流程

```text
create DRAFT
-> edit
-> READY
-> explicit start
-> IN_PROGRESS
-> Run completed + Node acceptance PASSED
-> REVIEW/PENDING
-> human accept -> DONE/ACCEPTED -> ARCHIVED
                  or
   request changes -> READY -> new WorkItem/Mission/Run
```

创建、编辑和 READY 不启动 WS。每次显式 start 在一个事务中冻结 Task version，创建新的 WorkItem、Mission、Run 和 `START_RUN` command；幂等键重放不能创建第二个执行。Run 失败、取消或不确定不会进入 REVIEW，只产生 attention 并保留重试入口。

## 2. Node 与 WS 执行流程

```text
Node poll START_RUN
-> validate target/digest/size
-> durable command + ACK
-> resolve workspaceRef/contextRefs locally
-> health/version/capability Gate
-> create one OpenCode Session
-> persist run/session/server/workspace binding
-> subscribe SSE
-> submit one Mission prompt_async
-> observe busy/retry/message/tool/permission/question
-> reconcile status/messages/pending interactions
-> explicit idle or completed non-tool-call assistant message
-> seal local evidence
-> execute trusted local acceptance profile
-> report short terminal projection
```

Runtime idle 与 acceptance 是两个独立事实。assistant 文本、HTTP 204、Session 存在、超时或 status map 缺项都不能单独完成 Run。验收 profile 未配置、无法启动或超时时返回 UNKNOWN；exit 非零或必需产物缺失返回 FAILED；只有 exit 0 且全部必需产物存在才返回 PASSED。

## 3. Interaction 与取消

permission/question 由 Node 映射为 Service 的短 Interaction；完整请求和回答仍只留 Node。用户响应经 Service durable command 重投，Node 落盘 ACK 后调用同一 Session 的原生 reply/reject，并在上游成功后报告 consumed。重复 resolve、command、ACK 或上游事件不能产生第二次回复。

取消由 Service 创建 `CANCEL_RUN`，Node 落盘后调用原生 `session.abort`。abort 失败或结果无法确认时进入 uncertain，不以杀 CLI 进程代替。

## 4. 通知流程

```text
Service state change
-> transactional notification outbox
-> authorized client polls one notification
-> external delivery by notificationId
-> delivery event returned to Service
```

通知投递与用户批准、Node 消费是三个独立状态。Service 不保存外部渠道 token 或完整回复正文；Hermes 等外部适配器不得绕过 Service 直接控制 Node。

## 5. Web 流程

浏览器只调用同源 BFF。读取使用只读 credential；Task mutation 使用单独写 credential，并要求 Sites 身份、同源校验、Fetch Metadata、CSRF header、`Idempotency-Key`、actor/reason 和已有对象的 `expectedVersion`。Web 以 ETag 条件轮询显示 Task/Run/Acceptance、attention、新鲜度和 Timeline，不读取 Node 原始证据。
