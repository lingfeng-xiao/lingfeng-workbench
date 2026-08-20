---
status: authoritative
authority: DF-0.3-control-loop
source_ref: documentation-index-v2
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# 文档入口

## 当前目标设计阅读顺序

1. [design-freeze-v0.3.md](design-freeze-v0.3.md)：冻结范围、保留项、非目标和变更控制。
2. [architecture.md](architecture.md)：三模块、外部组件、网络和数据边界。
3. [workflow.md](workflow.md)：工作流与通知两个业务闭环。
4. [decisions/ADR-001-module-boundaries.md](decisions/ADR-001-module-boundaries.md)：三模块基础决策。
5. [decisions/ADR-002-control-loop-and-agent-session.md](decisions/ADR-002-control-loop-and-agent-session.md)：出站控制环和 Agent 会话决策。
6. [contracts/README.md](contracts/README.md)：v2 唯一 HTTP 合同入口。
7. [contracts/control-loop-v2.md](contracts/control-loop-v2.md)：冻结的跨模块语义。
8. 各模块 `detailed-design.md`：模块内部详细设计。
9. [plans/v0.3-control-loop/README.md](plans/v0.3-control-loop/README.md)：分工、开发波次和 Gate。
10. [plans/v0.4-real-adoption/README.md](plans/v0.4-real-adoption/README.md)：真实 WS 解阻、低风险 canary、停止条件和渐进投入使用 Gate。

## 当前实现与证据

- [current-state.md](current-state.md)：当前已实现、已部署能力和阻塞；
- `contracts/client-api-v2.openapi.yaml`、`contracts/node-protocol-v2.openapi.yaml`：当前唯一可执行 HTTP 合同；
- [testing/e2e-1-fake.md](testing/e2e-1-fake.md)：fake Runtime 联调证据；
- [testing/e2e-v0.3-control-loop.md](testing/e2e-v0.3-control-loop.md)：DF-0.3 fake 两闭环、当前真实 WS 尝试、运行修复与构建哈希；
- [testing/e2e-1-real-ws.md](testing/e2e-1-real-ws.md)：v0.2 阶段真实 WS 历史尝试；
- [releases/v0.2.0-mvp1-rc1.md](releases/v0.2.0-mvp1-rc1.md)：当前冻结候选；
- [asset-inventory.md](asset-inventory.md)：历史资产和清理 Gate。

## 权威规则

- v0.3 开发以设计冻结、architecture、workflow、ADR-002 和 control-loop-v2 为目标权威；
- v0.2 协议实现与 OpenAPI 已从当前源码树移除；历史事实只在不可变 tag 和 release 记录中审计；
- 模块详细设计只能细化本模块，不能改变跨模块合同；
- 模块 agent 不得直接修改 `doc/contracts/`；
- `current-state.md` 只陈述事实，不授权编码、合并、部署或数据操作；
- 发生合同冲突时停止模块实现，由 architecture owner 通过 ADR/冻结版本处理。

## 文档状态

- `authoritative`：当前设计或合同；
- `current-evidence`：当前事实证据，不定义目标；
- `legacy-reference`：仅允许提炼行为和测试，不复用结构；
- `frozen-superseded`：已冻结且被取代，禁止作为新实现输入；
- `generated`：生成物，不构成设计依据。
