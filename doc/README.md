---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: v0.5.0-trusted-loop-rc1
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# 文档入口

## 当前目标设计阅读顺序

1. [design-freeze-v0.3.md](design-freeze-v0.3.md)：冻结范围、保留项、非目标和变更控制。
2. [architecture.md](architecture.md)：三模块、外部组件、网络和数据边界。
3. [workflow.md](workflow.md)：工作流与通知两个业务闭环。
4. [decisions/ADR-001-module-boundaries.md](decisions/ADR-001-module-boundaries.md)：三模块基础决策。
5. [decisions/ADR-002-control-loop-and-agent-session.md](decisions/ADR-002-control-loop-and-agent-session.md)：出站控制环和 Agent 会话决策。
6. [decisions/ADR-003-native-opencode-runtime.md](decisions/ADR-003-native-opencode-runtime.md)：Node→WS 原生 OpenCode Server 决策，取代 ADR-002 的 Runtime/Turn 部分。
7. [design-freeze-v0.5-business-loop.md](design-freeze-v0.5-business-loop.md)：以 Task 为中心、本机上下文留 Node 的下一版本产品冻结。
8. [decisions/ADR-004-task-centered-business-loop-and-local-context.md](decisions/ADR-004-task-centered-business-loop-and-local-context.md)：Task、Run、验收三轴与本机 ContextRegistry 决策。
9. [plans/v0.5-business-loop/README.md](plans/v0.5-business-loop/README.md)：先闭合一个真实 Task 的阶段纵切和 Gate。
10. [contracts/README.md](contracts/README.md)：v2 唯一 HTTP 合同入口。
11. [contracts/control-loop-v2.md](contracts/control-loop-v2.md)：冻结的跨模块语义；其 Node→Runtime 小节以 ADR-003 为准。
12. 各模块 `detailed-design.md`：模块内部详细设计。
13. [plans/v0.3-control-loop/README.md](plans/v0.3-control-loop/README.md)：历史分工、开发波次和 Gate。

## 当前实现与证据

- [research/opencode-native-runtime-2026-08-20.md](research/opencode-native-runtime-2026-08-20.md)：OpenCode 官方证据、本机 WS 实测、旧误区、原生能力对照和重建状态；
- [current-state.md](current-state.md)：当前已实现、已部署能力和阻塞；
- `contracts/client-api-v2.openapi.yaml`、`contracts/node-protocol-v2.openapi.yaml`、`contracts/task-api-v1.openapi.yaml`：当前唯一可执行 HTTP 合同；
- [testing/e2e-1-fake.md](testing/e2e-1-fake.md)：fake Runtime 联调证据；
- [testing/e2e-v0.3-control-loop.md](testing/e2e-v0.3-control-loop.md)：DF-0.3 fake 两闭环、当前真实 WS 尝试、运行修复与构建哈希；
- [testing/e2e-native-opencode-rebuild.md](testing/e2e-native-opencode-rebuild.md)：ADR-003 原生 OpenCode 重建的单测、reactor 与组合 E2E 证据；
- [testing/e2e-native-opencode-real-ws.md](testing/e2e-native-opencode-real-ws.md)：新 Node 原生路径连接真实本机 WS 的 Session、SSE、工具、idle 与取消实测；
- [testing/e2e-1-real-ws.md](testing/e2e-1-real-ws.md)：v0.2 阶段真实 WS 历史尝试；
- [releases/v0.5.0-trusted-loop-rc1.md](releases/v0.5.0-trusted-loop-rc1.md)：当前可信闭环冻结候选；
- [releases/v0.2.0-mvp1-rc1.md](releases/v0.2.0-mvp1-rc1.md)：历史冻结候选与不可变恢复点；
- [asset-inventory.md](asset-inventory.md)：历史资产和清理 Gate。

## 权威规则

- Service→Node v2 仍以设计冻结、architecture、workflow、ADR-002 和 control-loop-v2 为权威；Node→Runtime 以 ADR-003 为更高优先级权威；
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
