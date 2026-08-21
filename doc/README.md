---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: v0.5.0-trusted-loop-rc1
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# 文档入口

当前树只保留现行设计、合同、实现说明和仍有决策价值的证据。旧 Python、旧 CLI/三 Turn协议、旧计划、旧 MVP 文档和阶段性 E2E 已从当前树删除；需要历史审计时使用不可变 tag，而不是从当前文档推断旧行为。

## 当前权威阅读顺序

1. [releases/v0.5.0-trusted-loop-rc1.md](releases/v0.5.0-trusted-loop-rc1.md)：当前冻结范围、证据和已知边界。
2. [design-freeze-v0.5-business-loop.md](design-freeze-v0.5-business-loop.md)：Task、Run、验收三轴与产品边界。
3. [architecture.md](architecture.md)：Service、Node、Web、WS 的职责、网络和数据边界。
4. [workflow.md](workflow.md)：Task 业务闭环、Runtime 执行闭环和 Interaction/通知闭环。
5. [decisions/ADR-001-module-boundaries.md](decisions/ADR-001-module-boundaries.md)：三模块依赖边界。
6. [decisions/ADR-003-native-opencode-runtime.md](decisions/ADR-003-native-opencode-runtime.md)：Node 直接使用 OpenCode 原生 Server API。
7. [decisions/ADR-004-task-centered-business-loop-and-local-context.md](decisions/ADR-004-task-centered-business-loop-and-local-context.md)：Task、人工验收和 Node-only ContextRegistry。
8. [contracts/README.md](contracts/README.md)：三份当前 HTTP 合同和跨模块语义。
9. 三个模块的 `README.md` 与 `detailed-design.md`：模块职责和内部实现。

## 当前证据

- [current-state.md](current-state.md)：冻结版本、验证结果、真实 canary 和剩余稳定性 Gate。
- [research/opencode-native-runtime-2026-08-20.md](research/opencode-native-runtime-2026-08-20.md)：OpenCode 官方事实、本机 WS 实测和设计决定的区分。
- [testing/e2e-native-opencode-real-ws.md](testing/e2e-native-opencode-real-ws.md)：真实 Node→WS、Interaction、idle、验收和 Task 可信闭环。
- [testing/e2e-v0.5-task-business-loop.md](testing/e2e-v0.5-task-business-loop.md)：确定性 Task/FLOW/NOTIFY 组合 Gate。
- [asset-inventory.md](asset-inventory.md)：当前资产、历史恢复点和外部资源边界。

## 历史恢复点

- `v0.5.0-trusted-loop-rc1`：本轮清理前的完整可信闭环冻结树。
- `v0.2.0-mvp1-rc1`：旧 Java 三模块候选和 CLI Runtime 历史。
- `v0.1.0-server-recovered`：旧 Python/Plugin 救援基线。

历史 tag 只用于恢复和审计，不构成当前兼容承诺或实现输入。
