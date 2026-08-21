---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: web-current
owner: workbench-web
superseded_by: null
last_verified: 2026-08-21
---

# workbench-web

`workbench-web` 是部署到 OpenAI Sites 的私有 Task 界面和同源 BFF。Service 仍是唯一业务状态来源，Web 不形成第二套状态机。

## 职责

- 验证 Sites 身份并在服务端持有分离的 Service 读写 credential；
- 展示 Task/Run/Acceptance、attention、新鲜度、Node、Interaction 和 Timeline；
- 提供 Task create/edit/READY/start/accept/request changes/archive/restore；
- mutation 强制同源、Fetch Metadata、CSRF header、幂等键、actor/reason 和 expectedVersion；
- 对合同不匹配、超时、401/403/409/502 和超大响应 fail closed；
- 所有业务页面 no-store，不使用 D1/R2 或浏览器存储保存业务数据。

## 非职责

- 不连接 Node 或 WS，不读取本机证据；
- 不判断 Runtime 或验收是否成功，不自动接受 Task；
- 不把 Service credential、Session、路径、diff、日志或源码发送到浏览器；
- 不自行实现账号、密码、OAuth 或 Runtime Interaction 协议。

页面/BFF 边界见 [detailed-design.md](detailed-design.md)，平台边界见 [sites-boundary.md](sites-boundary.md)。
