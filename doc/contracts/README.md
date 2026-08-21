---
status: authoritative
authority: DF-0.3-control-loop
source_ref: contracts-index-v2
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# 跨模块合同入口

## 当前唯一合同

- `task-api-v1.openapi.yaml`：DF-0.5 Task 产品合同；使用独立 `/api/tasks/v1` 版本空间，不改变 Client API v2 的“创建即执行”兼容语义；
- `control-loop-v2.md`：Client v2、Node v2、Node/Runtime 会话协议的权威语义合同；
- `client-api-v2.openapi.yaml`、`node-protocol-v2.openapi.yaml` 编码冻结的 v2 HTTP 合同，`fixtures/v2/` 提供每类命令、事件、Interaction 和 Notification 的正反例；
- 在本目录运行 `npm ci && npm run lint && npm test` 执行严格 OpenAPI、fixture、64 KiB、800 字符、精确 Node 绑定和敏感字段 Gate；
- 当前源码树只提供 v2 路径和合同；未知版本、旧路径和未知字段全部 fail closed。

任何模块提出字段或状态变化时，只提交合同变更请求；由 architecture owner 评估是否需要 ADR 和冻结版本升级。

## 版本收口

- v0.2 协议没有真实兼容迁移需求，已按独立 Gate 从当前源码、测试、lint 和 OpenAPI 入口移除；
- v2 使用独立路径、DTO、持久化增量和 credential scope；旧路径不会被重定向到 v2；
- Service、Node、Web 只通过各自维护的协议映射消费 v2，不共享生成 DTO 或模块构建产物；
- v0.2 历史合同仍可从不可变 `v0.2.0-mvp1-rc1` tag 审计，不构成当前兼容承诺；
- v2 上线与凭证切换属于后续独立部署 Gate，本合同收口不授权生产迁移。
