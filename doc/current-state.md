---
status: current-evidence
authority: working-branch-evidence
source_ref: codex/three-module-mvp based on main@7d5fcc2d1208e98532b33e5e91c1a04195f3a438
owner: architecture
superseded_by: null
last_verified: 2026-08-19
---

# 当前状态

- 可信起点为 `main@7d5fcc2d1208e98532b33e5e91c1a04195f3a438`。
- `codex/three-module-mvp` 工作树已建立 Java Service、Java Node 与 Sites Web；旧 Python v0.1 仍以 `legacy-reference` 共存，尚未到删除 Gate。
- Service 与 Node 已统一为 Java 21、Spring Boot 3.5.5，并由根 Maven reactor 构建；Web 使用 Sites 官方 Vinext/React/TypeScript 骨架。
- 独立验证已通过：Service 5 个集成场景、Node 13 个 fake Service/Runtime 与本地证据场景、Web 11 个身份/上游/渲染场景，OpenAPI 严格 lint、Web lint 和三模块构建均通过。
- 本机组合 E2E-1 已使用临时自签 HTTPS、真实 Service JAR、真实 Node JAR 和 fake WS 进程完成：WorkItem/Mission/Run 进入 completed，Node 五类证据齐全，Service 敏感值扫描通过，Service 重启后状态仍可恢复，Web 生产构建能只读显示一致状态且不渲染 Node-only 值。证据说明见 `doc/testing/e2e-1-fake.md`。
- 真实 WS 无工具联调已实际尝试：首次发现 Windows `ws.cmd` 会截断多行 prompt，现已改为单行并增加参数测试；修复后完整 Mission 合同确实到达 `ws.exe`，但当前 WS 在 180 秒内没有产生 stdout、stderr、Session 或终态，Service 保持 `in_progress`。该环境阻塞记录在 `doc/testing/e2e-1-real-ws.md`，不能算作真实 WS Gate 通过。
- MVP-A 已证明 Hermes → SSH worker-stream → office-pc Node → 真实 WS 的无工具任务闭环。
- MVP-B 未证明：旧实现会丢失 Mission 合同、猜测裸审批对象，并把 Runtime 正常退出误判为业务完成。
- 旧 WS 文件任务曾越过预期工作区边界，因此新首轮 E2E 仍限定为无工具任务。
- 当前没有获授权的生产部署、Sites 发布、公司电脑安装、真实 WS 新链路验收、真实审批重放或遗留删除。
- 现有 Lingfeng Workbench Site 已核验为 active、owner、custom access、1 个允许用户、0 个外部访客；其旧 D1 业务表为空，仅保留迁移记录。

详细旧证据保存在 `doc/history/v0.1-rescue/` 与 Git 历史中。
