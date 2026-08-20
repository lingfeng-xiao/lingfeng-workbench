---
status: authoritative
authority: DF-0.3-control-loop
source_ref: asset-inventory-v2
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# 资产清单

| 资产 | status | 来源 | 所属领域 | 允许复用 | 禁止继承 | 清理动作与 Gate |
|---|---|---|---|---|---|---|
| `doc/design-freeze-v0.3.md`、`doc/architecture.md`、`doc/workflow.md`、ADR-002、`control-loop-v2.md` | authoritative | `DF-0.3-control-loop` | 全局 | v0.3 目标边界、流程与语义合同 | 被模块私自修改；解释为已实现 | 合同变更由架构 owner 通过 ADR/新冻结版本审核 |
| v0.2 Client/Node OpenAPI | frozen-superseded | `v0.2.0-mvp1-rc1` tag | 全局历史 | 历史审计 | 当前兼容承诺或实现输入 | 已从当前源码树移除；仅通过不可变 tag 恢复，不删除 tag |
| S1/N1/W1 `mvp.md`、S1 `domain-model.md`、N1 `runtime-contract.md` | frozen-superseded | `v0.2.0-mvp1-rc1` | 三模块 | 当前实现和历史验收审计 | 新开发结构、同步 Runtime SPI、Interaction 中断语义 | 保留到 v0.3 发布审计完成，目标见各模块 `detailed-design.md` |
| Python `lingfeng_workbench/` | legacy-reference | `968b88d9f869b0ed7a42c91e67c911f2c1e5b36c` / `7d5fcc2d1208e98532b33e5e91c1a04195f3a438` | Service/Node | 行为、失败证据、测试场景 | Python 结构、Kanban、Hermes 业务、裸审批 | E2E-1 真实 WS 与 smoke 通过后删除 |
| `pyproject.toml`、`plugin.yaml`、根 Python 入口 | legacy-reference | `968b88d9f869b0ed7a42c91e67c911f2c1e5b36c` | 历史 Plugin | 恢复审计 | 新模块构建与部署 | 与 Python 源码同 Gate 删除 |
| 旧 `docs/current-state.md` | current-evidence | `7d5fcc2d1208e98532b33e5e91c1a04195f3a438` | 历史 | MVP-A/B 事实 | 目标设计 | 移入 v0.1 历史目录 |
| 旧 `docs/design.md` | frozen-superseded | `7d5fcc2d1208e98532b33e5e91c1a04195f3a438` | 历史 | 不变数据边界 | Hermes 内部化、旧三部分设计 | 移入 v0.1 历史目录并标记 |
| `SERVER_RECOVERY.md`、`RECOVERY_MANIFEST.sha256` | current-evidence | `968b88d9f869b0ed7a42c91e67c911f2c1e5b36c` | 历史 | 恢复与来源证明 | 当前构建说明 | 移入 v0.1 历史目录；原路径语义写入说明 |
| Draft PR #1 | frozen-superseded | `3b8d8c0e5cdb5c44847c2a660bd6345b9e04d3ec` | 历史设计 | 审计 | 对象模型、路线图 | 2026-08-20 已注明被取代并关闭；远端头分支已删除 |
| Draft PR #7 | frozen-superseded | `ac20e056b5a569f8350016f4850b8249f8d69b52` | 历史治理 | 审计 | 自动继承治理实现 | 2026-08-20 已注明被取代并关闭；远端头分支已删除 |
| Draft PR #8 | frozen-superseded | `a451306184a64b899f562f6731667ad232a89d75` | 历史产品合同 | 安全测试思想 | 双空间/重对象模型 | 2026-08-20 已注明被取代并关闭；远端头分支已删除 |
| Draft PR #9 | frozen-superseded | `a2d18163481128fda5e4ef2bc84c184306152813` | 历史 Sites | 身份、fail-closed、no-store 测试思想 | D1/R2、旧 project ID 与实现 | 2026-08-20 已注明被取代并关闭；远端头分支已删除 |
| `codex/docs-trusted-baseline` | frozen-superseded | `e83b79a6f4902527d68b8a121a281265d8ecadb8` | 历史 | 审计 | 新实现起点 | 2026-08-20 已删除远端分支；恢复使用本行 exact SHA |
| `codex/three-module-mvp` | frozen-superseded | `cd2b8a02f091f61d454f6f886867af4dad83e7a2` / `v0.2.0-mvp1-rc1` | 集成 | 冻结版本审计 | 继续作为开发分支 | 已合入 main 并发布 tag；2026-08-20 已删除本地和远端分支 |
| 现有 Sites D1 `DB` 的 16 表 | frozen-superseded | Site live DB | Web 历史 | 空表核验 | 新业务持久化 | 首轮不写不删；单独破坏性 Gate |
| `__pycache__`、`target/`、`node_modules/`、构建输出 | generated | 本地 | 各模块 | 无 | 任何设计判断 | ignore，不提交 |

## 清理顺序

1. 新文档与合同成为 main 权威。
2. 关闭冻结 PR 并删除非 main 开发分支；不合并其内容。已于 2026-08-20 完成，远端仅保留 `main`。
3. 三模块独立测试与 fake E2E 通过；旧协议源码与合同已按独立授权移除。
4. 真实 WS 与生产形态 smoke 通过后才可另行评估 Python 活跃源码删除。
5. Sites D1 等外部资源只在独立破坏性 Gate 下清理。
