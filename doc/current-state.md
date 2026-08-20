---
status: current-evidence
authority: main-evidence
source_ref: main@6db31e52 plus DF-0.3 working-tree verification
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# 当前状态

- `DF-0.3-control-loop` 已在 2026-08-20 完成本地实现、旧协议移除和 fake 边界组合验收：Client API v2、Node Protocol v2、Service/Node/Web v2、出站 HTTPS 控制环、会话式 Runtime SPI、Interaction/通知闭环均已编码；当前源码树不存在旧 API controller/DTO/client/worker/config switch、旧 OpenAPI 或 lint 入口。实现仍在未提交工作区，未合并、部署、安装或生产验收，不属于当前线上运行能力。
- 本地验证使用 OpenJDK `21.0.12.1`：Service 5 项、Node 28 项、Web 32 项测试全部通过；根 Maven reactor、两份 v2 OpenAPI strict lint、26 份 fixtures、Web lint/test/build 通过。`E2E-FLOW` 使用真实 Service/Node JAR、fake session Runtime 和 Web production Worker 完成 3 Turn、Service 真实中断、outbox 恢复重放和 digest 匹配的 `SUCCEEDED/PASSED`；`E2E-NOTIFY` 完成通知投递、fake Hermes resolve、Node durable ACK、同一 Session 恢复和重复 delivery/resolve/ACK 幂等。详细证据见 `testing/e2e-v0.3-control-loop.md`。
- 可信工作区起点为 `main@6db31e52e3dd848dcfc05fd68db0dfd1a2ac3d9c`；全部 DF-0.3 变更仍未提交、未暂存。
- `main@cd2b8a02f091f61d454f6f886867af4dad83e7a2` 已冻结为 `v0.2.0-mvp1-rc1`，包含 Java Service、Java Node 与 Sites Web；旧 Python v0.1 仍以 `legacy-reference` 共存，尚未到删除 Gate。
- Service 与 Node 已统一为 Java 21、Spring Boot 3.5.5，并由根 Maven reactor 构建；Web 使用 Sites 官方 Vinext/React/TypeScript 骨架。
- v0.2 冻结基线及其旧协议仍可从不可变 `v0.2.0-mvp1-rc1` tag 审计，但不再位于当前源码树，也不构成兼容承诺。
- 本机组合 E2E-1 已使用临时自签 HTTPS、真实 Service JAR、真实 Node JAR 和 fake WS 进程完成：WorkItem/Mission/Run 进入 completed，Node 五类证据齐全，Service 敏感值扫描通过，Service 重启后状态仍可恢复，Web 生产构建能只读显示一致状态且不渲染 Node-only 值。证据说明见 `doc/testing/e2e-1-fake.md`。
- 当前代码已再次启动真实 Service JAR、真实 Node JAR 与真实 WS `0.0.0--202608171122` 运行无工具 Mission：`run_d45a6f45e87a476183cf5e221a701411` 成功创建并提交 Turn 1，但 30 秒内 WS 没有产生 Session ID、JSON 事件、stderr 或终态，Service 保持 `running`。动态能力检查仍显示 0 credential、无可用 model，因此真实同 Session 多 Turn、可信终态、Interaction 和重启持久化均未验证，绝不能算真实 WS Gate 通过。
- v2 WS Adapter 已移除旧一次性 Runtime 类型，单测证明同一观测到的 WS Session ID 可通过 `--session` 连续 3 Turn；它不宣称跨 Node 进程恢复或 durable Interaction，能力不足时继续 fail closed。
- 历史 MVP-A 曾证明 Hermes → SSH worker-stream → office-pc Node → 真实 WS 的无工具链路；它不替代当前 DF-0.3 WS Gate。
- MVP-B 未证明：旧实现会丢失 Mission 合同、猜测裸审批对象，并把 Runtime 正常退出误判为业务完成。
- 旧 WS 文件任务曾越过预期工作区边界，因此新首轮 E2E 仍限定为无工具任务。
- 个人服务器已以独立 user systemd service 运行 Service，监听 `127.0.0.1:18080`，SQLite 权限为 `0600`，停机备份和重启恢复已验证；公网目前使用会在重启后变化的 Cloudflare Quick Tunnel，不是稳定生产入口。
- 当前电脑仍安装冻结版本 Node JAR、独立 Java 21 Runtime 和 `Lingfeng Workbench Node v0.2` 计划任务；本轮开始时既有 PID 29856 正在运行且从未被验证命令指向或修改，但它在验证期间自行退出，最终任务状态为 Ready、无安装版 Node Java 进程。本轮未擅自重启。此前 Service 已看到 `office-pc` 持续心跳；旧 Python Node 任务仅禁用，未删除。公司电脑没有承担 workbench 源码开发。
- 现有 Lingfeng Workbench Private Site 已发布版本 4，仍为 owner、custom access、唯一允许用户、0 个外部访客，D1/R2 均为 `null`；服务端只读 credential 与 Service URL 已更新。匿名访问返回 401，现有 bypass 测试返回 403，因此已证明身份层 fail-closed，但尚未完成登录用户的页面级生产 E2E。
- CI/CD 已建立仓库级 Java、Web、v2 OpenAPI、Windows Wrapper Gate，以及只从不可变 `v*` 标签生成两个 JAR 和 SHA-256 的 GitHub Release 流程。CI/Release 不持有生产凭证，也不自动部署任何环境。
- Draft PR #1/#7/#8/#9 已标注被当前设计取代并关闭；其头分支、重复文档分支和已合入的三模块开发分支均已按 exact SHA 登记后删除。远端只保留 `main`，冻结恢复点为 `v0.2.0-mvp1-rc1`。
- 尚未通过真实 WS 终态、登录用户 Sites 页面和完整生产形态 smoke；因此不删除旧 Python、旧 D1 或其它受 Gate 保护的遗留资产。

详细旧证据保存在 `doc/history/v0.1-rescue/` 与 Git 历史中。
