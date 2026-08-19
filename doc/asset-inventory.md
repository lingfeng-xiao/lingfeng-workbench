---
status: authoritative
authority: main
source_ref: asset-inventory-v1
owner: architecture
superseded_by: null
last_verified: 2026-08-19
---

# 资产清单

| 资产 | status | 来源 | 所属领域 | 允许复用 | 禁止继承 | 清理动作与 Gate |
|---|---|---|---|---|---|---|
| `doc/architecture.md`、`doc/contracts/` | authoritative | 当前 main | 全局 | 目标边界与接口 | 被模块私自修改 | 合同变更由架构 owner 审核 |
| Python `lingfeng_workbench/` | legacy-reference | `968b88d9f869b0ed7a42c91e67c911f2c1e5b36c` / `7d5fcc2d1208e98532b33e5e91c1a04195f3a438` | Service/Node | 行为、失败证据、测试场景 | Python 结构、Kanban、Hermes 业务、裸审批 | E2E-1 真实 WS 与 smoke 通过后删除 |
| `pyproject.toml`、`plugin.yaml`、根 Python 入口 | legacy-reference | `968b88d9f869b0ed7a42c91e67c911f2c1e5b36c` | 历史 Plugin | 恢复审计 | 新模块构建与部署 | 与 Python 源码同 Gate 删除 |
| 旧 `docs/current-state.md` | current-evidence | `7d5fcc2d1208e98532b33e5e91c1a04195f3a438` | 历史 | MVP-A/B 事实 | 目标设计 | 移入 v0.1 历史目录 |
| 旧 `docs/design.md` | frozen-superseded | `7d5fcc2d1208e98532b33e5e91c1a04195f3a438` | 历史 | 不变数据边界 | Hermes 内部化、旧三部分设计 | 移入 v0.1 历史目录并标记 |
| `SERVER_RECOVERY.md`、`RECOVERY_MANIFEST.sha256` | current-evidence | `968b88d9f869b0ed7a42c91e67c911f2c1e5b36c` | 历史 | 恢复与来源证明 | 当前构建说明 | 移入 v0.1 历史目录；原路径语义写入说明 |
| Draft PR #1 | frozen-superseded | `3b8d8c0e5cdb5c44847c2a660bd6345b9e04d3ec` | 历史设计 | 审计 | 对象模型、路线图 | 新文档合入后注明取代并关闭 |
| Draft PR #7 | frozen-superseded | `ac20e056b5a569f8350016f4850b8249f8d69b52` | 历史治理 | 审计 | 自动继承治理实现 | 新文档合入后注明取代并关闭 |
| Draft PR #8 | frozen-superseded | `a451306184a64b899f562f6731667ad232a89d75` | 历史产品合同 | 安全测试思想 | 双空间/重对象模型 | 新文档合入后注明取代并关闭 |
| Draft PR #9 | frozen-superseded | `a2d18163481128fda5e4ef2bc84c184306152813` | 历史 Sites | 身份、fail-closed、no-store 测试思想 | D1/R2、旧 project ID 与实现 | 新文档合入后注明取代并关闭 |
| `codex/docs-trusted-baseline` | frozen-superseded | `e83b79a6f4902527d68b8a121a281265d8ecadb8` | 历史 | 审计 | 新实现起点 | exact SHA 登记后删除远端分支 |
| 其它四个冻结 PR 分支 | frozen-superseded | 上述 exact head | 历史 | 审计 | 合并或 cherry-pick | PR 关闭后删除远端分支 |
| 现有 Sites D1 `DB` 的 16 表 | frozen-superseded | Site live DB | Web 历史 | 空表核验 | 新业务持久化 | 首轮不写不删；单独破坏性 Gate |
| `__pycache__`、`target/`、`node_modules/`、构建输出 | generated | 本地 | 各模块 | 无 | 任何设计判断 | ignore，不提交 |

## 清理顺序

1. 新文档与合同成为 main 权威。
2. 关闭冻结 PR 并删除非 main 开发分支；不合并其内容。
3. 三模块独立测试与 E2E-1 通过。
4. 真实 WS 与生产形态 smoke 通过后删除 Python 活跃源码。
5. Sites D1 等外部资源只在独立破坏性 Gate 下清理。
