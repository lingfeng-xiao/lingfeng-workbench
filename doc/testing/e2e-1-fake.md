---
status: current-evidence
authority: working-branch-evidence
source_ref: codex/three-module-mvp
owner: integration
superseded_by: null
last_verified: 2026-08-19
---

# E2E-1 fake Runtime 联调证据

## 形态

- 以临时自签证书启动真实 `workbench-service` 可执行 JAR，Node 使用独立 truststore 通过 HTTPS 访问。
- 通过 Client API 原子创建 WorkItem 与首个 Mission。
- 启动真实 `workbench-node` 可执行 JAR；Runtime 边界使用独立 fake WS 进程，依次产生 Session 和带正确 Mission digest 的 `PASSED` 结构化终态。
- 使用 Web 的 Vinext production server 和 Sites 身份请求头读取同一 Service 状态；Service credential 只在服务端进程环境中。
- 所有进程、数据库、证书、truststore、Node 状态和工作区均位于临时目录；测试结束后进程已停止，没有部署或外部写入。

## 验证结果

- WorkItem、Mission 和 Run 均进入 `completed`，短结果为 fake Runtime 的验收摘要。
- Node 本地存在 `mission.json`、`normalized-events.ndjson`、`runtime-events.ndjson`、`runtime-stderr.log` 和 `result.md`。
- Service SQLite/WAL 字节扫描未发现 Runtime Session、Node 工作区绝对路径或原始 `lingfeng.terminal` 事件。
- 停止并以同一 SQLite 重新启动 Service 后，completed 状态仍可查询。
- Web production build 显示同一 completed WorkItem，且未显示 Session、原始事件或本地路径。

## 联调发现并修复

首次联调暴露了 Runtime 进程启动失败会在 Run 仍为 `assigned` 时上报失败终态，而 Service 原状态机只允许 `running` 接收终态。当前规则允许 `assigned` 接收明确的 `EXECUTION_FAILED`/`EXECUTION_INTERRUPTED` 非 PASSED 终态，同时继续拒绝未启动的成功终态；对应 Service 集成测试已加入。

## 尚未覆盖

- fake Runtime 只证明模块合同和数据边界，不等同于真实 WS 验收。
- 未执行个人服务器反向代理 smoke、Sites 发布或公司电脑 Node 安装。
- Interaction 精确恢复属于 MVP-2。
