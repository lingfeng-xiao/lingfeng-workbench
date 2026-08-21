---
status: authoritative
authority: DF-0.5-business-loop
source_ref: web-design-w3-task-p1
owner: workbench-web
superseded_by: null
last_verified: 2026-08-21
---

# workbench-web 详细设计 W3

## 0. v0.5 P1 增量

Web 现以 Task 为主入口，同时保留 W2 的 WorkItem/Interaction/Node 历史只读页。新增 `/` Task 池、`/tasks/new`、`/tasks/:id` 和 `/attention`。Task 详情展示业务/执行/验收三轴、Node 在线性、lastObservedAt、stale、全部短 Run 历史与 append-only Timeline；`IN_PROGRESS` 时使用 ETag 每 4 秒条件读取，页面隐藏时降为 15 秒，离开活动态后停止高频刷新。

浏览器只访问固定同源 `/api/tasks` BFF。BFF 校验 Sites 身份、同源 Origin、Fetch Metadata、`X-Workbench-CSRF: 1` 和 Idempotency-Key，以身份摘要生成稳定 actor，并使用独立 read/write credential 调 `/api/tasks/v1`；浏览器不能提供 actor、Service URL 或 credential。create 还必须显式确认数据边界。操作按钮只按 Service `allowedActions` 渲染，create 以外 mutation 转发 expectedVersion，409 只显示安全冲突文案。

Service/Web 只处理安全 alias 和短摘要；严格 parser 拒绝未知字段和未知状态。绝对路径、Session、原始事件、diff、日志和产物不得进入页面模型或浏览器 bundle。D1/R2 继续为 null，不使用 localStorage/sessionStorage。

以下小节同时约束 Task 主入口和兼容的 WorkItem/Interaction/Node 历史只读页。

## 1. 模块职责

Web 是私有 Task 界面和同源 BFF。它展示 Service 已持久化的业务/执行/验收状态，并提供受约束的 Task mutation；它不解决 Runtime Interaction、不读取 Node 或 Runtime，也不自行判断完成。

## 2. Sites 边界

- 复用现有 Lingfeng Workbench Private Site；
- 保留 Vinext/React/TypeScript、`sites()` 插件和 Worker-compatible ESM；
- `.openai/hosting.json` 的现有 opaque project ID 不变，D1/R2 均为 `null`；
- 每次发布前独立核验 owner、custom access、唯一允许用户和零外部访客；
- Sites 服务端校验平台身份后，用分离的 read/write credential 调 Service；
- 浏览器不持有 Service URL、credential 或业务缓存。

详细平台约束继续以 `sites-boundary.md` 为准。

## 3. 页面信息架构

### `/`

展示活动 Task、业务/执行/验收三轴、attention、Node 在线摘要和最近观测时间。每个活动项显示当前阶段、短进度、Run 状态和允许动作。

### `/work-items/:id`

展示 WorkItem、Mission 合同摘要、Run 状态、阶段/进度、`resumable`、Interaction 和短时间线。重要通知只显示类型和 `pending/delivered/dead_letter` 投影，不显示微信身份或消息内容。

### `/interactions`

只读展示 pending/resolved/delivered/consumed 状态、Run、checkpoint、短提示、允许决策和时间。不得展示完整 Agent prompt，也不提供批准按钮。

### `/nodes`

展示在线/离线、能力、最后心跳、当前 Run 投影和最后同步时间。离线是 Service 投影，页面不尝试连接 Node 验证。

## 4. 服务端数据访问

所有 Service 调用集中在 server-only client/BFF：

- 请求前验证两个 Sites 身份 header；
- 固定允许的 Task API v1 与 Client API v2 路径，禁止任意 URL 代理；
- 读取和 mutation 使用分离的最小 scope credential；
- mutation 额外校验 Origin、Fetch Metadata、CSRF header、Idempotency-Key 和 expectedVersion；
- 设置短超时、64 KiB 响应上限和严格 schema 解析；
- 拒绝未知字段，不把上游对象直接传给组件；
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
taskId, title, businessStatus, acceptanceStatus, attentionState
workItemId, status
missionId, revision, objective/acceptance short text
runId, status, phaseCode, progressSummary, resultSummary
resumable, lastSyncedAt
interaction short projection
node online projection
notification delivery projection
```

禁止进入展示模型：绝对 workspace、missionDigest、Session ID、本地路径、tool call、diff、原始事件、完整日志、完整结果和 Service credential。Task 只允许安全的 workspaceRef/contextRef alias。

## 6. 状态表达

- `waiting_interaction` 明确显示“等待输入”，不显示为失败；
- `uncertain` 明确显示“结果不可信，需要关注”，不归类为成功；
- 网络错误显示“暂时无法读取”，不回退到缓存旧状态；
- progress 带“最后同步于”，不宣称实时；
- `dead_letter` 只表示通知投递失败，不改变 Run 业务结果；
- Node 离线不自动把 Run 显示为失败。

## 7. 前端结构

保持当前路由结构和以下内部边界：

```text
app/_lib/task-service       server-only Task HTTP and auth
app/_lib/task-bff           identity/origin/CSRF/idempotency boundary
app/_lib/task-contracts     strict Task parsers
app/_lib/workbench-service  legacy read-only Client v2 adapter
app/_lib/contracts          strict Client v2 parsers
app/_lib/presentation       projection and labels
app/_components             stateless display components
app/*                       route composition
```

不引入全局客户端状态库、前端数据库、localStorage/sessionStorage 或第二套领域模型。自动刷新只重新请求 no-store/ETag 页面数据。

## 8. 测试与验收

使用 fake Service 覆盖：身份缺失、读写 scope、Origin/CSRF/Fetch Metadata、幂等与 expectedVersion、401/403/409/timeout/502、超大响应、未知字段和状态、Task 三轴、历史页以及敏感字段不渲染。保持 lint、test、build 和 Sites metadata 校验。

模块测试不调用真实 Service、不修改 Sites 权限、不发布、不写 D1/R2，也不需要微信或 Runtime。
