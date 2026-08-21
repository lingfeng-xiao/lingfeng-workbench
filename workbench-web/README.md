# workbench-web

Lingfeng Workbench 的私有 Sites 控制面。它通过同源 BFF 读写 `workbench-service` 保存的 Task 短控制状态，不拥有业务状态，也不连接 `workbench-node`。

## 本地验证

要求 Node.js 22.13 或更高版本。

```bash
npm ci
npm run lint
npm test
```

`npm test` 会先生成 Worker-compatible ESM 构建，再用本地 fake Service 验证身份、独立读写凭证、CSRF、幂等转发、Task 状态页面、条件刷新与敏感字段过滤。测试不访问真实 Service 或 Sites。

## 运行时配置

从 `.env.example` 复制本地配置，并在 Sites 运行时以 secret/环境变量提供相同字段：

- `WORKBENCH_SERVICE_BASE_URL`：Service 根地址，生产环境必须使用 HTTPS；不要附加 `/api/client/v2`。本地组合联调允许 `http://localhost` 或 `http://127.0.0.1`。
- `WORKBENCH_SERVICE_READ_TOKEN`：仅有 Client API 读取权限的 Sites 机器凭证。
- `WORKBENCH_SERVICE_WRITE_TOKEN`：仅有 Task mutation 权限的独立 Sites 机器凭证，不进入浏览器 bundle。
- `WORKBENCH_SERVICE_TIMEOUT_MS`：可选，默认 5000 ms，范围 100–30000 ms。

浏览器不应收到这些配置。业务数据不写入 D1、R2、`localStorage` 或 `sessionStorage`。

## 页面

- `/`：活动 Task 池。
- `/tasks/new`：创建 DRAFT Task；不会启动 WS。
- `/tasks/:id`：Task/Run/Acceptance 三轴、显式动作、Run 历史与 Timeline。
- `/attention`：REVIEW、失败、不确定、Node 离线和 stale 聚合。
- `/work-items/:id`：WorkItem、Mission 和 Run 短时间线。
- `/interactions`：只读 Interaction 生命周期（不提供审批或回复操作）。
- `/nodes`：Node 在线状态、能力、当前 Run、最后心跳和最后同步时间。

Service 调用约定：Sites Worker 从运行时读取上述环境变量，只调用 `/api/tasks/v1` 和兼容历史页所需的 `/api/client/v2` 固定路径。浏览器 mutation 只访问同源 `/api/tasks` BFF；BFF 验证 Sites 身份、同源 Origin、Fetch Metadata、`X-Workbench-CSRF` 与 `Idempotency-Key`，再注入稳定 actor。所有响应必须通过严格合同解析且不超过 64 KiB；页面响应统一 `no-store`。

部署、访问策略和运行时 secret 变更需要各自的显式 Gate；本模块的构建或测试通过不会自动触发部署。
