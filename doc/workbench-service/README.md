---
status: authoritative
authority: main
source_ref: service-design-s2
owner: workbench-service
superseded_by: null
last_verified: 2026-08-19
---

# workbench-service

`workbench-service` 是 Workbench 唯一业务状态来源。它是个人服务器上的独立 Spring Boot 进程，通过 Client API 服务 Hermes、Sites Web 等授权客户端，通过 Node Protocol 服务执行电脑。

当前目标详细设计见 [detailed-design.md](detailed-design.md)；当前本地实现事实见 `doc/current-state.md` 和 v2 OpenAPI。

## 职责

- 原子创建 WorkItem 与首个 Mission。
- 管理 Mission 分配、Run 状态转换和显式验收终态。
- 管理 Node 身份绑定、注册、心跳和能力投影。
- 管理持久化 Node 命令、Interaction 精确响应和通知投递 outbox。
- 持久化最多 800 字符的控制摘要、幂等记录和短审计时间线。
- 按 `hermes`、`sites`、单 Node 三类凭证实施最小权限。

## 非职责

- 不调用或恢复 Agent Runtime。
- 不保存 Runtime Session、原始事件、完整结果、日志、产物、绝对路径或源码。
- 不实现 Hermes、Sites 页面或 Node 的本地执行逻辑。
- 不访问 Node 本地数据库，不依赖其它模块的源码或 DTO。
- 不保存微信身份或 token，不主动连接 Node，不替 Hermes 发送消息。

## 运行

运行需要 Java 21，并通过环境或外部配置提供至少 32 字符且彼此不同的 Hermes/Sites 凭证，以及按 `nodeId` 绑定的 Node 凭证。数据库默认位于 `./var/workbench-service.db`，生产环境必须显式指定 `WORKBENCH_DATABASE_URL` 并在升级前备份。

Service 假定 HTTPS 在可信反向代理或运行环境终止；应用本身不生成或管理证书。
