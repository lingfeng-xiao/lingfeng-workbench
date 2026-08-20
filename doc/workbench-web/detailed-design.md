---
status: authoritative
authority: DF-0.3-control-loop
source_ref: web-design-w2
owner: workbench-web
superseded_by: null
last_verified: 2026-08-20
---

# workbench-web 详细设计 W2

## 1. 模块职责

Web 是私有、只读、非实时的控制状态观察面。它展示 Service 已持久化的工作进度、阻塞、Node 和通知投影，不创建任务、不解决 Interaction、不读取 Node 或 Runtime。

## 2. Sites 边界

- 复用现有 Lingfeng Workbench Private Site；
- 保留 Vinext/React/TypeScript、`sites()` 插件和 Worker-compatible ESM；
- `.openai/hosting.json` 的现有 opaque project ID 不变，D1/R2 均为 `null`；
- 每次发布前独立核验 owner、custom access、唯一允许用户和零外部访客；
- Sites 服务端校验平台身份后，用只读机器 credential 调 Service；
- 浏览器不持有 Service URL、credential 或业务缓存。

详细平台约束继续以 `sites-boundary.md` 为准。

## 3. 页面信息架构

### `/`

展示活动 WorkItem、最近终态、等待 Interaction 数、Node 在线摘要和最近同步时间。每个活动项显示当前阶段、短进度、Run 状态和是否等待输入。

### `/work-items/:id`

展示 WorkItem、Mission 合同摘要、Run 状态、阶段/进度、`resumable`、Interaction 和短时间线。重要通知只显示类型和 `pending/delivered/dead_letter` 投影，不显示微信身份或消息内容。

### `/interactions`

只读展示 pending/resolved/delivered/consumed 状态、Run、checkpoint、短提示、允许决策和时间。不得展示完整 Agent prompt，也不提供批准按钮。

### `/nodes`

展示在线/离线、能力、最后心跳、当前 Run 投影和最后同步时间。离线是 Service 投影，页面不尝试连接 Node 验证。

## 4. 服务端数据访问

所有 Service 调用集中在 server-only client：

- 请求前验证两个 Sites 身份 header；
- 固定允许的 Client API v2 路径，禁止任意 URL 代理；
- 使用只读 scope credential；
- 设置短超时、64 KiB 响应上限和严格 schema 解析；
- 丢弃未知字段，不把上游对象直接传给组件；
- 401/403/timeout/5xx/非 JSON/合同不匹配映射为有界页面错误；
- 不记录 Authorization、完整上游 body 或身份 header。

业务响应统一：

```text
Cache-Control: no-store
Pragma: no-cache
```

## 5. 展示模型

组件只接收页面专用 projection：

```text
workItemId, title, status
missionId, revision, objective/acceptance short text
runId, status, phaseCode, progressSummary, resultSummary
resumable, lastSyncedAt
interaction short projection
node online projection
notification delivery projection
```

禁止进入展示模型：workspaceRef、missionDigest、Session/Turn ID、本地路径、tool call、diff、原始事件、完整日志、完整结果和 Service credential。

## 6. 状态表达

- `waiting_interaction` 明确显示“等待输入”，不显示为失败；
- `uncertain` 明确显示“结果不可信，需要关注”，不归类为成功；
- 网络错误显示“暂时无法读取”，不回退到缓存旧状态；
- progress 带“最后同步于”，不宣称实时；
- `dead_letter` 只表示通知投递失败，不改变 Run 业务结果；
- Node 离线不自动把 Run 显示为失败。

## 7. 前端结构

保持当前路由结构，建议内部边界：

```text
app/_lib/service-client     server-only HTTP and auth
app/_lib/contracts          local strict parsers for v2 response
app/_lib/presentation       projection and labels
app/_components             stateless display components
app/*                       route composition
```

不引入全局客户端状态库、前端数据库、localStorage/sessionStorage 或第二套领域模型。自动刷新不是 W2 正确性要求；如后续增加，只能重新请求 no-store 页面数据。

## 8. 测试与验收

使用 fake Service 覆盖：身份缺失、只读 scope、401/403/timeout/502、超大响应、未知字段和状态、空/运行/等待/失败/uncertain/离线页面、同步时间、通知失败投影以及敏感字段不渲染。保持 lint、test、build 和 Sites metadata 校验。

模块测试不调用真实 Service、不修改 Sites 权限、不发布、不写 D1/R2，也不需要微信或 Runtime。
