---
status: authoritative
authority: main
source_ref: node-design-n2
owner: workbench-node
superseded_by: null
last_verified: 2026-08-19
---

# workbench-node

`workbench-node` 是安装在执行电脑上的本地 Runtime 宿主。它通过 HTTPS 主动连接
`workbench-service`，执行明确绑定到本 Node 的 Mission，并将完整执行证据留在本机。

当前目标详细设计见 [detailed-design.md](detailed-design.md)；Node 不只是进程启动器，而是一个活动 Run 的本机 Agent 会话控制器。

## 职责

- 上报 Node 身份、Runtime 能力和心跳，轮询持久化命令；Service 永不回连 Node。
- 校验 `targetNodeId`、`runtimeKind`、Mission digest 和短文本边界。
- 管理异步 Agent Session、多 Turn、Interaction、checkpoint、恢复、取消及归一化事件。
- 在本地 SQLite 中保存 Run 索引和 durable outbox。
- 在本地 Run 目录保存 Mission 快照、归一化事件、Runtime 原始事件、stderr 和完整结果。
- 只向 Service 发送最多 800 字符的控制摘要和 `resumable` 投影。
- 对重复 Assignment、重复 ACK、断线重投和不可信终态安全处理。

## 非职责

- 不拥有 WorkItem、Mission 或 Run 的全局业务状态，不决定跨电脑调度。
- 不访问 Service 数据库，不依赖 `workbench-service` 的 Java 类或 DTO。
- 不实现文件工具、工作区沙箱或操作系统隔离。
- 不向 Service 上传 Runtime Session、命令行、绝对路径、原始事件、完整日志或产物。
- 不实现 Hermes 或 Web 逻辑；真实 Runtime 不支持的 Interaction/恢复能力不得由 Node 伪造。

## 代码边界

- `connection`：Node Protocol HTTPS 客户端、64 KiB 限制和错误映射。
- `orchestration`：异步 RunSupervisor、命令串行化和 outbox 投递。
- `session`：Agent Session、Turn、checkpoint 和 Interaction 绑定。
- `localstate`：SQLite、durable command/outbox 与本地证据文件。
- `runtime`：稳定 SPI；`runtime/ws` 是首个适配器。
- `config`：Node 身份、Service 地址、凭证、Runtime 和本地 workspaceRef 映射。

`workspaceRef` 只是 Service 合同中的不透明标识。Node 将其解析为 Runtime 的本地工作目录，
但该解析不构成沙箱或文件能力验收。
