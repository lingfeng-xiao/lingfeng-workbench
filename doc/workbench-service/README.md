---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: service-current
owner: workbench-service
superseded_by: null
last_verified: 2026-08-21
---

# workbench-service

`workbench-service` 是 Task、执行投影、验收和审计的全局控制面。它提供 Task API v1、Client API v2 和 Node Protocol v2，但永不回连 Node。

## 职责

- Task CRUD、READY、显式 start、人工 accept/request changes、archive/restore；
- 同事务创建 WorkItem/Mission/Run/START_RUN 与 append-only TaskEvent；
- Run/Interaction/Node/notification 的 durable、幂等和单调状态机；
- scoped bearer credential、大小/字段校验和乐观锁；
- 向 Web/Hermes 提供有界业务与控制投影。

## 非职责

- 不调用、恢复或解释 OpenCode Runtime；
- 不保存 Session、绝对路径、原始事件、conversation、diff、完整日志、验收报告或产物；
- 不执行本机验收命令，不替用户把 successful Run 自动接受为 DONE；
- 不访问 Node 数据库，不主动连接 Node，不持有外部消息渠道 token。

数据库、事务和状态映射见 [detailed-design.md](detailed-design.md)。
