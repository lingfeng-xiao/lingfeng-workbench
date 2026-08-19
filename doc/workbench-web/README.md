---
status: authoritative
authority: architecture
source_ref: web-mvp-w1
owner: workbench-web
superseded_by: null
last_verified: 2026-08-19
---

# workbench-web 职责

`workbench-web` 是部署到 OpenAI Sites 的私有只读界面。它是 `workbench-service` 的客户端，不是第二个控制面。

## 负责

- 在服务端确认 Sites 提供的当前访问者身份。
- 使用独立、只读的机器凭证调用 Client API v1。
- 展示 WorkItem、Mission、Run、Interaction 和 Node 的短控制状态。
- 对缺失身份、Service 拒绝、超时、不可用和合同不匹配给出安全失败界面。
- 对所有业务页面返回 `Cache-Control: no-store`。

## 不负责

- 创建或修改 WorkItem、Mission、Run 和 Interaction。
- 判断任务是否完成、调度 Node 或恢复 Runtime。
- 直接连接 `workbench-node` 或读取执行电脑。
- 保存业务数据、完整日志、完整产物、Runtime 对话、Session、绝对路径或源码。
- 自己实现账号、密码、OAuth 或 Workspace 成员关系。

## 依赖方向

Web 只依赖 `doc/contracts/client-api.openapi.yaml`。它不依赖其它模块源码、DTO、数据库、构建输出或 Node Protocol。跨模块合同变更由架构 owner 统一处理。
