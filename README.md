# lingfeng-workbench

`lingfeng-workbench` 是一个由三个独立模块组成的个人工作执行系统：

- `workbench-service`：业务状态与最小控制数据；
- `workbench-node`：执行电脑上的 agent runtime 宿主；
- `workbench-web`：部署在 OpenAI Sites 的只读结构化界面。

Hermes 是仓库外部的 Client API 调用方，不参与本仓库业务逻辑。完整产物、日志、Runtime 对话和本地上下文只保存在执行电脑。

## 当前阶段

`v0.2.0-mvp1-rc1` 是从 `main@7d5fcc2d1208e98532b33e5e91c1a04195f3a438` 建立的三模块冻结候选。旧 Python 源码是 `legacy-reference`，只用于提炼行为和验证迁移，在 MVP-1 真实联调与生产形态 smoke 通过前不删除，也不是新代码的结构模板。

权威入口见 [doc/README.md](doc/README.md)，资产状态见 [doc/asset-inventory.md](doc/asset-inventory.md)。Draft PR、Issue、其它分支和旧 Sites D1 均不自动构成当前设计。

## 不变边界

- 服务端只保存 WorkItem、Mission、Run、Interaction、Node 的最小控制信息与必要审计；
- 电脑之间不迁移任务、Runtime Session、工作区或产物；
- `workbench-node` 对接 runtime-neutral SPI，WS 只是首个适配器；
- Kanban 不进入目标设计；
- 公司电脑只运行明确批准的 Node 版本，不开发、提交或推送源码；
- 合并、部署、权限变化、生产写入和遗留删除分别需要明确 Gate。
