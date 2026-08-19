---
status: authoritative
authority: main
source_ref: main@7d5fcc2d1208e98532b33e5e91c1a04195f3a438
owner: architecture
superseded_by: null
last_verified: 2026-08-19
---

# 文档入口

## 阅读顺序

1. [architecture.md](architecture.md)：产品边界、依赖方向和数据所有权。
2. [contracts/client-api.openapi.yaml](contracts/client-api.openapi.yaml)：外部客户端合同。
3. [contracts/node-protocol.openapi.yaml](contracts/node-protocol.openapi.yaml)：Service 与 Node 合同。
4. 对应模块目录中的 README 与 MVP 文档。
5. [current-state.md](current-state.md)：当前实现事实与迁移进度。
6. [testing/e2e-1-fake.md](testing/e2e-1-fake.md)：当前组合联调证据与未覆盖 Gate。
7. [testing/e2e-1-real-ws.md](testing/e2e-1-real-ws.md)：真实 WS 尝试、修复和当前阻塞。
8. [releases/README.md](releases/README.md)：版本、CI 和发布 Gate。
9. [releases/v0.2.0-mvp1-rc1.md](releases/v0.2.0-mvp1-rc1.md)：冻结候选范围和未满足 Gate。
10. [asset-inventory.md](asset-inventory.md)：历史资产能否复用及其清理 Gate。

## 权威规则

- `doc/architecture.md` 与 `doc/contracts/` 描述当前接受的目标设计。
- 模块文档只能细化本模块，不能改变跨模块合同。
- `doc/current-state.md` 只陈述事实，不授权实现、部署或数据操作。
- `doc/history/`、Draft PR、Issue、其它分支和 Sites 历史资源只作审计输入。
- 发生冲突时先停止工作，由架构 owner 更新权威文档后再继续。

## 文档状态

- `authoritative`：当前设计或合同。
- `current-evidence`：当前事实证据，不定义目标。
- `legacy-reference`：仅允许提炼行为和测试，不复用结构。
- `frozen-superseded`：已冻结且被取代，禁止作为实现输入。
- `generated`：生成物，不构成设计依据。
