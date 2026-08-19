---
status: current-evidence
authority: main-evidence
source_ref: v0.2.0-mvp1-rc1 plus deployment verification
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# 当前状态

- 可信起点为 `main@7d5fcc2d1208e98532b33e5e91c1a04195f3a438`。
- `main@cd2b8a02f091f61d454f6f886867af4dad83e7a2` 已冻结为 `v0.2.0-mvp1-rc1`，包含 Java Service、Java Node 与 Sites Web；旧 Python v0.1 仍以 `legacy-reference` 共存，尚未到删除 Gate。
- Service 与 Node 已统一为 Java 21、Spring Boot 3.5.5，并由根 Maven reactor 构建；Web 使用 Sites 官方 Vinext/React/TypeScript 骨架。
- 独立验证已通过：Service 5 个集成场景、Node 13 个 fake Service/Runtime 与本地证据场景、Web 11 个身份/上游/渲染场景，OpenAPI 严格 lint、Web lint 和三模块构建均通过。
- 本机组合 E2E-1 已使用临时自签 HTTPS、真实 Service JAR、真实 Node JAR 和 fake WS 进程完成：WorkItem/Mission/Run 进入 completed，Node 五类证据齐全，Service 敏感值扫描通过，Service 重启后状态仍可恢复，Web 生产构建能只读显示一致状态且不渲染 Node-only 值。证据说明见 `doc/testing/e2e-1-fake.md`。
- 真实 WS 无工具联调已实际尝试：首次发现 Windows `ws.cmd` 会截断多行 prompt，现已改为单行并增加参数测试；修复后完整 Mission 合同确实到达 `ws.exe`，但当前 `ws providers list` 为 0 credential，`ws models` 无可用模型。无人值守官方 OAuth 尝试没有完成授权且没有生成 credential，已安全取消。该环境阻塞记录在 `doc/testing/e2e-1-real-ws.md`，不能算作真实 WS Gate 通过。
- MVP-A 已证明 Hermes → SSH worker-stream → office-pc Node → 真实 WS 的无工具任务闭环。
- MVP-B 未证明：旧实现会丢失 Mission 合同、猜测裸审批对象，并把 Runtime 正常退出误判为业务完成。
- 旧 WS 文件任务曾越过预期工作区边界，因此新首轮 E2E 仍限定为无工具任务。
- 个人服务器已以独立 user systemd service 运行 Service，监听 `127.0.0.1:18080`，SQLite 权限为 `0600`，停机备份和重启恢复已验证；公网目前使用会在重启后变化的 Cloudflare Quick Tunnel，不是稳定生产入口。
- 当前电脑已安装冻结版本 Node JAR 与独立 Java 21 Runtime，并由 `Lingfeng Workbench Node v0.2` 启动任务运行；Service 已看到 `office-pc` 持续心跳。旧 Python Node 任务仅禁用，未删除。公司电脑没有承担 workbench 源码开发。
- 现有 Lingfeng Workbench Private Site 已发布版本 4，仍为 owner、custom access、唯一允许用户、0 个外部访客，D1/R2 均为 `null`；服务端只读 credential 与 Service URL 已更新。匿名访问返回 401，现有 bypass 测试返回 403，因此已证明身份层 fail-closed，但尚未完成登录用户的页面级生产 E2E。
- CI/CD v1 已建立仓库级 Java、Web、OpenAPI、Windows Wrapper Gate，以及只从不可变 `v*` 标签生成两个 JAR 和 SHA-256 的 GitHub Release 流程。CI/Release 不持有生产凭证，也不自动部署任何环境。
- 尚未通过真实 WS 终态、登录用户 Sites 页面和完整生产形态 smoke；因此不删除旧 Python、旧 D1 或其它受 Gate 保护的遗留资产。

详细旧证据保存在 `doc/history/v0.1-rescue/` 与 Git 历史中。
