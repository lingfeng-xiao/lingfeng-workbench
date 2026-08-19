# workbench-web

Lingfeng Workbench 的私有、只读 Sites 界面。它只读取 `workbench-service` 保存的短控制状态，不拥有业务状态，也不连接 `workbench-node`。

## 本地验证

要求 Node.js 22.13 或更高版本。

```bash
npm ci
npm run lint
npm test
```

`npm test` 会先生成 Worker-compatible ESM 构建，再用本地 fake Service 验证身份、只读凭证、错误边界、状态页面与敏感字段过滤。测试不访问真实 Service 或 Sites。

## 运行时配置

从 `.env.example` 复制本地配置，并在 Sites 运行时以 secret/环境变量提供相同字段：

- `WORKBENCH_SERVICE_BASE_URL`：Service 根地址，生产环境必须使用 HTTPS；不要附加 `/api/client/v1`。
- `WORKBENCH_SERVICE_READ_TOKEN`：仅有 Client API 读取权限的 Sites 机器凭证。
- `WORKBENCH_SERVICE_TIMEOUT_MS`：可选，默认 5000 ms，范围 100–30000 ms。

浏览器不应收到这些配置。业务数据不写入 D1、R2、`localStorage` 或 `sessionStorage`。

## 页面

- `/`：活动工作、最近终态和 Node 摘要。
- `/work-items/:id`：WorkItem、Mission 和 Run 短时间线。
- `/interactions`：只读待处理 Interaction。
- `/nodes`：Node 在线状态、能力和最后心跳。

部署、访问策略和运行时 secret 变更需要各自的显式 Gate；本模块的构建或测试通过不会自动触发部署。
