---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: post-v0.5-cleanup
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# 资产清单

| 资产 | 当前状态 | 当前用途 | 恢复或清理规则 |
|---|---|---|---|
| `workbench-service/` | authoritative implementation | Task、Run、Interaction、通知与全局控制面 | 只按 Task/API/数据库 Gate 演进 |
| `workbench-node/` | authoritative implementation | 出站控制、本机 Context、原生 OpenCode Session 和验收 | 不恢复 CLI/三 Turn/terminal JSON |
| `workbench-web/` | authoritative implementation | Sites 私有 UI 与同源 Task BFF | 浏览器不持有 Service credential |
| `doc/contracts/` | authoritative contract | Task v1、Client v2、Node v2 和语义合同 | 合同变化先更新 ADR/冻结版本 |
| `doc/decisions/ADR-003*`、`ADR-004*` | authoritative decisions | Runtime 与业务闭环当前决策 | 不与旧 ADR/计划并列解释 |
| `doc/testing/e2e-native-opencode-real-ws.md` | current evidence | 真实本机 WS 与可信业务闭环 | 保留失败和成功样本 |
| `doc/testing/e2e-v0.5-task-business-loop.md` | current evidence | 确定性组合回归 | 只记录最新冻结实现 Gate |
| `integration/control-loop-e2e.mjs` | active test | Task/FLOW/NOTIFY 与可选真实 WS Gate | 不承载生产数据 |
| `v0.5.0-trusted-loop-rc1` | immutable history | 清理前冻结恢复点 | tag 不移动、不覆盖 |
| `v0.2.0-mvp1-rc1`、`v0.1.0-server-recovered` | immutable history | 旧 Java/Python 审计 | 当前树不保留重复可执行源码和文档 |
| `target/`、`node_modules/`、Web 构建输出 | generated | 本地/CI 构建 | ignore，不作为设计证据 |

## 已从当前树删除

- Python `lingfeng_workbench/`、根 Python 入口、`pyproject.toml` 和 `plugin.yaml`；
- v0.1 恢复文档副本、v0.3/v0.4/v0.5 已完成计划；
- v0.2/v0.3 阶段性 E2E、旧模块 `mvp.md`、`domain-model.md`、`runtime-contract.md`；
- ADR-002 和 DF-0.3 重复权威入口；仍有效的出站控制、数据边界和幂等规则已收敛到当前架构、合同及 ADR-003/004。

上述内容均可从不可变 tag 恢复。删除只作用于仓库当前树，不删除 tag、Release、生产数据、旧 D1、已安装程序或任何外部资源。
