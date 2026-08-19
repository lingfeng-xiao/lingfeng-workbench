# lingfeng-workbench

lingfeng-workbench 是个人使用的工作控制面：用户从 Hermes 发起和查看任务，由指定电脑上的 workbench-node 调用 WS 等 agent runtime 执行。Hermes 只保存必要的控制信息，完整产物留在执行任务的电脑。

## 当前状态

快照日期：2026-08-19（Asia/Shanghai）

| 范围 | 状态 |
|---|---|
| MVP-A：基础执行闭环 | 已跑通 |
| MVP-B：一次审批后恢复 | 被具体问题阻塞 |
| 完整 v0.2 产品化 | 已冻结，不参与当前 MVP 判断 |

详细证据和冻结边界见 [当前状态](docs/current-state.md)。当前认可的产品边界和长期方向见 [精简设计](docs/design.md)。

## 可信来源

- GitHub main 是已接受设计和源码的可信来源。
- Draft PR、Issue 和其它分支用于提案、执行记录与历史审计；除非合并到 main，否则不代表已接受设计。
- 新电脑应先阅读本页、docs/current-state.md 和 docs/design.md，再决定是否开始工作。
- 完整产品化草案保留在 [Draft PR #1](https://github.com/lingfeng-xiao/lingfeng-workbench/pull/1)，仅作历史输入，不是当前执行授权。

## 数据边界

服务端只保存任务标识、短摘要、运行状态、交互状态、Node 状态和必要审计信息。文档、代码、日志、报告、凭证以及 Runtime 原始对话和事件保留在对应电脑，不上传到 Hermes、GitHub 或其它服务。

不同电脑的工作区与上下文完全独立；任务、Runtime Session 和本地产物不在电脑之间迁移或同步。

## 当前运行形态

当前已验证的救援链路是：

Hermes Plugin → 受限 SSH worker-stream → 办公电脑 workbench-node → WS

未来页面计划由 Sites 承载，但 Sites、数据库和完整页面均不属于当前已跑通链路。Kanban 不是目标设计，也不作为过渡方案或长期需求池。
