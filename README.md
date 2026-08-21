# lingfeng-workbench

`lingfeng-workbench` 是一个由三个独立模块组成的个人工作执行系统：

- `workbench-service`：业务状态与最小控制数据；
- `workbench-node`：执行电脑上的 agent runtime 宿主；
- `workbench-web`：部署在 OpenAI Sites 的结构化界面与同源 Task BFF。

Hermes 是仓库外部的 Client API 调用方，不参与本仓库业务逻辑。完整产物、日志、Runtime 对话和本地上下文只保存在执行电脑。

## 当前阶段

`v0.5.0-trusted-loop-rc1` 是当前仓库级冻结候选：Service→Node→本机 WS 已改为 OpenCode 原生 HTTP/SSE Session 路径，并闭合 Task、Run、Node 本机客观验收和人工接受。`v0.2.0-mvp1-rc1` 继续作为历史不可变恢复点；其 CLI/三 Turn Runtime 协议不构成当前兼容承诺。

当前候选已通过 Node 39 项、Service 8 项、Web 54 项、三份 OpenAPI strict lint、26 份 v2 fixtures、根 reactor package、真实 WS 可信业务 canary，以及真实 Service/Node JAR 与 Web production Worker 的确定性组合 E2E。该结论证明一个可信闭环，不等同于部署授权或长期稳定性认证。旧 Python 源码仍受独立删除 Gate 保护，未被本轮清理。

权威入口见 [doc/README.md](doc/README.md)，资产状态见 [doc/asset-inventory.md](doc/asset-inventory.md)。Draft PR、Issue、其它分支和旧 Sites D1 均不自动构成当前设计。

## 不变边界

- 服务端只保存 Task、WorkItem、Mission、Run、Interaction、Node 的最小控制信息与必要审计；
- 电脑之间不迁移任务、Runtime Session、工作区或产物；
- `workbench-node` 对接 runtime-neutral SPI，WS 只是首个适配器；
- Kanban 不进入目标设计；
- 公司电脑只运行明确批准的 Node 版本，不开发、提交或推送源码；
- 合并、部署、权限变化、生产写入和遗留删除分别需要明确 Gate。
