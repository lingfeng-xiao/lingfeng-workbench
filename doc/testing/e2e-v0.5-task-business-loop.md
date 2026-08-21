---
status: current-evidence
authority: DF-0.5-business-loop
source_ref: v0.5.0-trusted-loop-rc1
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# v0.5 P1 Task 业务闭环验证

## 组合 Gate

最终证据目录为系统临时目录中的 `lingfeng-control-loop-e2e-r8FNiS`。测试使用重新打包的真实 Service JAR、真实 Node JAR、确定性 fake Session Runtime 和 Web production Worker；没有部署、安装 Node、提交、推送、发送消息或写生产数据。

验证链路：

```text
create DRAFT -> edit -> mark READY -> explicit start
-> 2 progress -> REVIEW/PENDING
-> request changes -> READY -> second explicit start
-> 2 progress -> REVIEW/PENDING
-> human accept -> DONE/ACCEPTED
-> archive -> restore -> Service restart -> archive
```

可审计结果：

- create、edit、READY 后 `work_items` 仍为 0；
- 首次 start 以同一 Idempotency-Key 重放，只创建 1 个 WorkItem/Run/Session；
- 两次执行各产生 2 条 `RUN_PROGRESS_UPDATED`，总计 4 条；
- successful Run 都只使 Task 进入 `REVIEW/PENDING`；
- 退回后的第二个 Run 使用新 WorkItem/Mission/Run，mission revision 为 2，旧 Run 未覆盖；
- 人工 accept 显式提供 delivery summary、commit SHA 和 HTTPS PR URL 后才进入 `DONE/ACCEPTED`；
- archive/restore、同库 Service 重启和最终 archive 后仍保留 2 个 Run、20 条 append-only Timeline；
- Web production Worker 在重启前后都能展示业务/执行/验收三轴与两次执行历史；
- Service SQLite/WAL 不含 workspace 绝对路径、fake Session ID、`runtime-events.ndjson` 或 `conversation.ndjson`。

同一次完整组合运行还复验了既有 FLOW 的 Service 中断/outbox 重放和 NOTIFY 的 Interaction/通知/重复 ACK 幂等，均通过。

## 发现并修复的缺陷

第一次双 Run 组合运行保留了失败证据：第二个 Run 已在 Service 创建为 assigned，但 Node `RunSupervisor` 仍持有首个已终态的 `activeCommand`，因而拒绝第二个 Run。修复后，Supervisor 在持久化终态后关闭完成 Session、清理活动绑定，并串行接续已排队的新 Run；runtime event sink 绑定 expected runId，避免旧 Session 的迟到事件污染新 Run。新增单测证明同一 Node 进程可顺序完成两个 durable Run。

## 尚未通过的真实 WS Gate

本轮没有把 fake 结果声明为真实 WS 成功。当前存在三个可复现的停止条件：

1. 当前执行环境没有配置 `WORKBENCH_WS_BASE_URI`，无法通过 ADR-003 要求的显式 loopback endpoint/version Gate；
2. `workbench.acceptance.profiles` 默认为空，真实 `LocalCommandAcceptanceEvaluator` 对未登记 execution profile 必须以 `UNKNOWN` 收口；
3. 用户本轮明确禁止 commit/push，而 P1 真实人工验收要求真实 commit SHA 和 PR URL。

因此真实开发 Task 的最终 Gate 需要新的外部副作用授权、可用 endpoint，以及一个受支持 execution profile 的客观 AcceptanceEvaluator。失败样本和未通过项保持原样，不使用 fake 或人工改库补成成功。
