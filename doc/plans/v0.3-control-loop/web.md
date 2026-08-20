---
status: authoritative
authority: DF-0.3-control-loop
source_ref: plan-v0.3-web
owner: workbench-web
superseded_by: null
last_verified: 2026-08-20
---

# Web 开发任务 W2

## W2-001：Client API v2 只读适配

- 依赖：A-001、A-003；
- 增加 server-only v2 client 和严格 response parser；
- 只允许 v2 只读路径并拒绝旧协议路径；
- 验收：只允许固定 Service 路径，credential 不进入浏览器 bundle/HTML/log。

## W2-002：首页进度投影

- 展示活动项阶段、短进度、waiting interaction、lastSyncedAt；
- 区分 completed、failed、uncertain 和 offline；
- 验收：空、运行、等待、断网和旧数据状态均有明确文案。

## W2-003：WorkItem 详情时间线

- 展示多轮执行产生的服务端短时间线，而非本地 Turn 内容或标识；
- 展示 Interaction 生命周期和重要 notification delivery 状态；
- 验收：dead letter 不改变 Run 结果，Node offline 不伪造 Run failure。

## W2-004：Interactions 和 Nodes

- Interaction 只读显示 pending/resolved/delivered/consumed；
- Node 显示 current Run、last heartbeat 和同步时间；
- 不增加批准按钮或 Node 直连；
- 验收：五种 Interaction 状态、online/offline 和 unknown contract 安全显示。

## W2-005：失败与数据边界测试

- 身份缺失时不调用 Service；
- 401/403/timeout/502/非 JSON/超 64 KiB/未知字段 fail closed；
- 敏感字段注入不渲染；
- 所有业务页 no-store；
- lint、test、build、Worker-compatible ESM 和 hosting metadata 校验通过。

## 模块交付顺序

```text
W2-001 -> W2-002 -> W2-003 -> W2-004 -> W2-005
```

Web 任务不修改 Sites 权限、project ID、D1/R2、生产 secret 或线上部署。
