---
status: authoritative
authority: main
source_ref: ADR-001
owner: architecture
superseded_by: null
last_verified: 2026-08-19
---

# ADR-001：三模块边界

## 决定

- 一个仓库只包含 `workbench-service`、`workbench-node`、`workbench-web` 三个业务模块。
- Hermes 是外部 Client API 调用方。
- Service 和 Node 使用 Java 21、Spring Boot、Maven；Web 使用 Sites 官方 Vinext/React/TypeScript 骨架。
- Service 使用个人服务器本地 SQLite；Web 不使用 Sites D1/R2 保存业务数据。
- Service 与 Node 通过 HTTPS pull 协议通信，模块间不共享代码。
- WorkItem 保留；一次 Client 创建在同一事务中产生 WorkItem 与首个 Mission。
- Web MVP 只读；Interaction 与精确恢复属于 MVP-2。
- Kanban 不进入目标设计，不迁移旧 Python、Hermes 或 Sites D1 数据。

## 结果

各模块可以使用 fake 边界独立开发。跨模块变化必须先更新 OpenAPI 合同，不能通过共享 DTO 或数据库绕过边界。
