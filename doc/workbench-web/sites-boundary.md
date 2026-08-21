---
status: authoritative
authority: architecture
source_ref: web-sites-current
owner: workbench-web
superseded_by: null
last_verified: 2026-08-21
---

# Sites 边界

## 项目与访问

- 复用现有 Sites 项目 `appgprj_6a841dad1a8881919399cc5bced2c838`，不创建第二个 Site。
- `.openai/hosting.json` 中 `d1`、`r2` 均保持 `null`。
- 目标部署形态是 Private Site；每次部署前仍须由部署 Gate 重新核验 owner、custom access、唯一允许用户和零外部访客。
- 使用 Sites/Vinext 官方骨架、`sites()` 构建插件和 Cloudflare Worker-compatible ESM。

## 身份与授权

- 私有访问由 Sites 平台策略承担；服务端页面还必须同时收到 `oai-authenticated-user-id` 与 `oai-authenticated-user-email`。
- 身份 header 缺失时只进入平台拥有的 `/signin-with-chatgpt` 流程，仓库不实现认证路由。
- Sites 身份只证明当前访问者身份，不能替代 Service 授权。
- Service 机器凭证只存在于 Sites 运行时；Task 读取和 mutation 使用分离的最小 scope credential，兼容 Client v2 页面只使用 read credential。浏览器、HTML、日志和错误信息不得包含任何凭证。
- 所有 mutation 必须同时通过 Sites 身份、同源 Origin、Fetch Metadata、固定 CSRF header、Idempotency-Key 和 expectedVersion（create 除外）校验。
- 生产 Service URL 必须是 HTTPS。本地测试仅允许 `localhost` 或 `127.0.0.1` 使用 HTTP。

## 数据与缓存

- 页面只解析 Task API v1 或 Client API v2 合同中的短控制字段；未知字段和未知状态 fail closed，不向组件透传。
- `missionDigest`、`workspaceRef` 可参与 Service 合同，但 MVP-W1 不渲染；Session、本地路径、原始事件和 artifact 即使被上游误传也不会进入页面模型。
- 所有业务页面显式返回 `Cache-Control: no-store` 和 `Pragma: no-cache`。
- 不使用 D1、R2、浏览器存储或站点文件保存业务状态；Service 是唯一事实来源。
- Service 超时、拒绝、不可用或响应不符合合同，页面显示有界错误，不展示缓存旧状态，也不猜测业务状态。
