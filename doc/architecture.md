---
status: authoritative
authority: main
source_ref: architecture-v1
owner: architecture
superseded_by: null
last_verified: 2026-08-19
---

# 三模块架构

## 模块

仓库只包含三个业务模块：

1. `workbench-service`：唯一业务状态来源，对外提供 Client API 与 Node API。
2. `workbench-node`：执行电脑上的本地执行器和 Runtime 宿主。
3. `workbench-web`：部署到 OpenAI Sites 的私有只读界面。

Hermes 是外部客户端。它可以通过 Client API 创建工作、查询状态以及在后续 MVP 中解决 Interaction，但不拥有 Workbench 状态、不调度 Node、不连接 Runtime。

## 依赖方向

```text
Hermes --------> Client API <-------- Sites Web
                         |
                  workbench-service
                         |
                    Node Protocol
                         |
                   workbench-node
                         |
                     Runtime SPI
                         |
                WS / Codex / Claude Code
```

- 三模块不共享源码、Entity、DTO、数据库或 `common/core/shared-model` 模块。
- Web 不直连 Node，浏览器不直连 Service。
- Node 不访问 Service 数据库，Service 不访问 Node 本地状态。
- 两个 Java 模块分别依据版本化 OpenAPI 实现自己的边界模型。

## 数据所有权

Service 允许保存：稳定 ID、短目标、短验收、授权摘要、状态、短进度、短结果、Node 投影、幂等信息和必要审计。控制摘要最多 800 个字符，协议消息最多 64 KiB。

Node 本地保存：Mission 快照、Runtime Session/handle、原始事件、stderr、完整结果和本地证据。绝对路径、Runtime Session、完整日志和产物不得进入 Service API。

Sites Web 不拥有业务数据，D1/R2 绑定保持为空。浏览器存储只可用于非权威 UI 偏好。

## Runtime 边界

Node 不实现文件工具或操作系统沙箱。Runtime 及其适配器负责工作区、工具和隔离；Node 负责选择 Runtime profile、管理生命周期和保存本地证据。首个 E2E 只运行无工具 Mission，不代表文件能力已经验收。

## 部署边界

- Service 是个人服务器上的独立 Spring Boot 进程，使用本地 SQLite 文件。
- Node 是 Windows 优先的 Spring Boot 非 Web 应用，只主动访问 Service HTTPS API。
- Web 复用现有 Private Site，通过 Sites 服务端以只读机器凭证访问 Service。
- 公司电脑不承担仓库源码开发。
