---
status: authoritative
authority: architecture
source_ref: web-mvp-w1
owner: workbench-web
superseded_by: null
last_verified: 2026-08-19
---

# MVP-W1

## 用户能力

- `/`：查看活动工作数、在线 Node、最近终态、活动 WorkItem 和最近终态 WorkItem。
- `/work-items/:id`：查看 WorkItem 状态、Mission 执行合同摘要及 Run 短时间线。
- `/interactions`：查看待处理 Interaction 的精确 ID、Run、checkpoint 和提示摘要，不提供解决操作。
- `/nodes`：查看 Node 在线投影、最后心跳和 capability。

## 安全失败

- 缺少 Sites 身份时不读取 Service。
- Service 401/403 显示访问被拒绝，不泄露上游响应体。
- Service 5xx、网络失败或超时显示暂时不可用。
- 非 JSON、超过 64 KiB、字段缺失、未知状态或字段类型错误均视为合同不匹配。
- 未识别的 Service 字段不进入页面模型；页面不渲染 workspaceRef、missionDigest、Runtime Session、绝对路径、原始事件或 artifact。

## 验收

- `npm run lint` 通过。
- `npm test` 使用 fake Service 覆盖缺失身份、401、403、502、超时、错误合同、空页面、全部 WorkItem 状态、Mission/Run/Interaction/Node 投影及敏感字段不渲染。
- 生成物保留 Vinext `sites()` 和 Worker-compatible ESM，hosting metadata 绑定既有 project ID 且 D1/R2 为 `null`。
- 本 MVP 不部署、不改变 Sites 权限、不配置生产 secret，也不调用真实 Service。

## 后续 Gate

生产形态 smoke 前必须分别确认：Service HTTPS 可用、只读 scope 凭证已创建、Private Site 访问策略符合要求、精确 Web commit 已批准部署。MVP-W2 是否增加 Interaction 操作另立设计和授权 Gate。
