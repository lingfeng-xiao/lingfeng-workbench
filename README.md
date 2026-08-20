# lingfeng-workbench

`lingfeng-workbench` 是一个由三个独立模块组成的个人工作执行系统：

- `workbench-service`：业务状态与最小控制数据；
- `workbench-node`：执行电脑上的 agent runtime 宿主；
- `workbench-web`：部署在 OpenAI Sites 的只读结构化界面。

Hermes 是仓库外部的 Client API 调用方，不参与本仓库业务逻辑。完整产物、日志、Runtime 对话和本地上下文只保存在执行电脑。

## 当前阶段

`v0.2.0-mvp1-rc1` 仍是已发布冻结候选；当前未提交工作区以 `main@6db31e52e3dd848dcfc05fd68db0dfd1a2ac3d9c` 为起点实现 `DF-0.3-control-loop`。Client API 与 Node Protocol 已收口为 v2 唯一协议，v0.2 协议实现只保留在历史 tag，不再位于当前源码树。

DF-0.3 已通过本地 v2 单元/集成 Gate，以及真实 Service/Node JAR、fake session Runtime/Hermes 和 Web production Worker 的两个组合闭环。真实 WS 仍因本机无 credential/可用模型而未通过，不能视为生产能力。旧 Python 源码仍是受独立删除 Gate 保护的 `legacy-reference`，未被本轮清理。

权威入口见 [doc/README.md](doc/README.md)，资产状态见 [doc/asset-inventory.md](doc/asset-inventory.md)。Draft PR、Issue、其它分支和旧 Sites D1 均不自动构成当前设计。

## 不变边界

- 服务端只保存 WorkItem、Mission、Run、Interaction、Node 的最小控制信息与必要审计；
- 电脑之间不迁移任务、Runtime Session、工作区或产物；
- `workbench-node` 对接 runtime-neutral SPI，WS 只是首个适配器；
- Kanban 不进入目标设计；
- 公司电脑只运行明确批准的 Node 版本，不开发、提交或推送源码；
- 合并、部署、权限变化、生产写入和遗留删除分别需要明确 Gate。
