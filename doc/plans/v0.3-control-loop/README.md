---
status: authoritative
authority: DF-0.3-control-loop
source_ref: plan-v0.3-control-loop
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# v0.3 Control Loop 开发计划

## 1. 目标

在不扩大数据和网络边界的前提下交付两个可重复的端到端闭环：

1. `E2E-FLOW`：单 Node、单 Run、单 Agent Session、多 Turn、断网重放和可信完成；
2. `E2E-NOTIFY`：Interaction 暂停、Hermes 通知、微信等价响应、Node ACK、同 Session 恢复。

## 2. 所有权

- **主 agent / architecture owner**：全局文档、ADR、v2 OpenAPI、合同 fixtures、集成 harness、E2E 和最终一致性审查；
- **Service agent**：只改 `workbench-service/` 和 `doc/workbench-service/`；
- **Node agent**：只改 `workbench-node/` 和 `doc/workbench-node/`；
- **Web agent**：只改 `workbench-web/` 和 `doc/workbench-web/`；
- 模块 agent 不直接改 `doc/contracts/`，合同问题提交给 architecture owner；
- 任一模块不得依赖另一模块的源码、DTO、数据库、测试类或构建输出。

## 3. 开发波次

```text
Wave 0  Contract Gate（主 agent，串行）
  -> Wave 1A Service foundation ┐
  -> Wave 1B Node foundation    ├─ 可并行，均使用合同 fixtures
  -> Wave 1C Web projection     ┘
  -> Wave 2  Service + Node fake integration
  -> Wave 3  Notification + Interaction integration
  -> Wave 4  real Runtime and production-shape smoke
```

### Wave 0：合同 Gate

- A-001：新增 Client API v2 OpenAPI；
- A-002：新增 Node Protocol v2 OpenAPI；
- A-003：为每类 command/event/Interaction/notification 建立正反例 JSON fixtures；
- A-004：严格 lint、64 KiB/800 字符边界和敏感字段 deny-list 检查；
- A-005：记录 v2 唯一协议和 v0.2 历史审计策略。

Wave 0 完成前，三个模块只能搭测试骨架和内部接口，不得实现推测的跨模块 payload。

## 4. 独立交付物

- Service 计划：`service.md`；
- Node 计划：`node.md`；
- Web 计划：`web.md`；
- 联调、E2E、发布和清理 Gate：`integration.md`。

每个任务必须提供：范围、变更文件域、测试证据、数据泄漏检查和未完成项。测试通过不自动授权合并或部署。

## 5. 分支和合并顺序

建议分支：

```text
codex/v03-contracts
codex/v03-service
codex/v03-node
codex/v03-web
codex/v03-integration
```

先合并合同，再让模块分支 rebase/merge 最新 main；模块可独立合并，但默认关闭 v2 功能或保持无生产凭证，直到集成 Gate 通过。集成分支只做跨模块 fixture/harness 和必要合同修正，不吸收模块私有重构。

## 6. Definition of Done

模块任务完成必须满足：

- 只修改授权目录；
- v2 合同测试通过，旧协议路径不存在；
- 重启、重复消息和异常状态有测试；
- Service/Web 敏感字段扫描通过；
- 文档与实现状态一致；
- 未暗含部署、凭证、公司电脑开发或遗留删除；
- 所有新失败路径都 fail closed，不把 UNKNOWN 解释为完成。

## 7. Gate 顺序

```text
Design Freeze（本文件集）
  -> Contract PR
  -> Service PR / Node PR / Web PR
  -> Fake E2E PR
  -> Notification E2E PR
  -> Real Runtime Gate
  -> Server deploy Gate
  -> Node install Gate
  -> Sites deploy Gate
  -> Production smoke Gate
  -> Legacy deletion Gate
  -> Release/tag Gate
```

后一个 Gate 不因前一个测试通过而自动获得授权。
