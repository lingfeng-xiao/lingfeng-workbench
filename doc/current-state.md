---
status: current-evidence
authority: main-evidence
source_ref: v0.5.0-trusted-loop-rc1-plus-cleanup
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# 当前状态

## 已冻结能力

- 当前冻结候选为 `v0.5.0-trusted-loop-rc1`，对应 `main@3b40b27e562f3cfde28c41bce3b69aaecb0416c1`。
- 唯一执行路径是 `Service -> Node -> 本机 WS/OpenCode Server`；Service 不回连 Node。
- 一个 Run 绑定一个本机 OpenCode Session，只发送一次初始 Mission prompt；后续 message 只服务于真实 question/permission/Interaction。
- Node 使用 SSE 做实时主通道，并用 status/messages/pending interaction 做 reconciliation；status 缺项不等于 idle。
- Runtime idle 与业务验收分离。Node 本机受信 `executionProfile` 独立产生 `PASSED/FAILED/UNKNOWN`；successful Run 只使 Task 进入 `REVIEW/PENDING`，人工接受后才进入 `DONE/ACCEPTED`。
- Session、绝对路径、原始事件、完整 conversation、diff、stdout/stderr、产物和验收报告只保留在 Node。
- Web 提供 Task 创建、编辑、READY、start、accept、request changes、archive/restore，以及 Task/Run/Acceptance 三轴和 Timeline；浏览器只访问同源 BFF。

## 验证结果

- Node 39 项、Service 8 项、Web 54 项通过；Web lint/production build 通过。
- 三份 OpenAPI strict lint、26 份 v2 fixtures、根 Maven reactor package 通过。
- 当前清理后冻结版本 JAR 的确定性组合证据为 `lingfeng-control-loop-e2e-PJRk0g`：Task 最终 `ARCHIVED/ACCEPTED`，2 个 Run、20 条 Timeline，FLOW/NOTIFY、同 Session Interaction、重复 delivery/resolve/ACK、Service 重启、Web render 和敏感证据扫描均通过。
- 真实 WS 可信业务闭环证据为 `lingfeng-control-loop-e2e-QTDYy1`：Run `run_265de6a7893b43489f2e73e71f6a3792` completed，Node acceptance PASSED，Task `REVIEW/PENDING -> DONE/ACCEPTED`，Service 重启和 Web render 通过。
- GitHub CI run `32455581456` 与 Release run `32455691155` 均成功。

## 当前树清理

旧 Python/Plugin 执行路径、旧 CLI/三 Turn/terminal JSON 代码、重复冻结/ADR、旧计划、旧 MVP 文档和阶段性 E2E 已从当前树删除。历史恢复使用 `v0.5.0-trusted-loop-rc1`、`v0.2.0-mvp1-rc1` 和 `v0.1.0-server-recovered`，不得把历史内容重新解释为当前执行路径。

## 尚未宣称

- 当前结果是一个真实 canary 加确定性回归，不是长期稳定性认证。
- 仍需连续多个真实开发 Run、真实 permission、活动 Run 跨 Node/WS 恢复、pending Interaction 重启对账和受控 SSE 故障恢复。
- 本轮清理不部署、不安装、不写生产数据，也不删除旧 D1、Release、tag 或其它外部资产。
