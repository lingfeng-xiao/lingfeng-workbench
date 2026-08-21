---
status: current-evidence
authority: main-evidence
source_ref: v0.5.0-trusted-loop-rc1
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# 当前状态

- v0.5 P1 冻结候选已完成可测试的 Task 耐久业务纵切：独立 `/api/tasks/v1`、前向 Liquibase、TaskEvent 同事务 append、显式 start 原子创建既有 WorkItem/Mission/Run/START_RUN、Node ContextRegistry fail-closed、本机 alias/context 解析、Run 终态投影、同源 Web BFF、Task 池/详情/关注页、4 秒 ETag 条件刷新、人工验收和归档/恢复均已实现。Task/Run/Acceptance 三轴保持独立，successful Run 只进入 `REVIEW/PENDING`。当前验证为 Service 8 项 + Node 39 项全通过；Web lint/build 与 54 项测试、三份 OpenAPI strict lint、26 份 v2 fixtures 均通过。根 reactor 因本机内存不能稳定 fork Service 测试 JVM，但模块测试分别通过，统一 reactor package 通过。
- 2026-08-21 最终 fake 组合证据 `lingfeng-control-loop-e2e-NkUKdy` 使用 `0.5.0-trusted-loop-rc1` 真实 Service/Node JAR 和 Web production Worker，从 DRAFT 创建/编辑/READY（零 WorkItem）开始，经幂等 start、4 条进度、`REVIEW/PENDING`、退回、第二个独立 Run、人工 accept、archive/restore、Service 重启，最终保留 `ARCHIVED/ACCEPTED`、2 个 Run 和 20 条 Timeline；FLOW/NOTIFY、同 Session Interaction、重复 delivery/resolve/ACK 幂等和 Service SQLite/WAL 敏感扫描均通过。较早的通过样本 `lingfeng-control-loop-e2e-fYa1qb` 曾发现并促成修复 `RunSupervisor` 首个终态后未释放 Node、导致后续 Run 永久 assigned 的缺陷；现在终态 Session 关闭后可串行启动下一 Run，并用 runId 绑定 event sink 隔离迟到事件。
- v0.5 真实 WS 可信业务闭环已在系统临时 Maven canary 中通过。证据 `lingfeng-control-loop-e2e-QTDYy1`：Service Task `task_64e1eae54b9e467d90b57eea77a82b52` 显式启动 Run `run_265de6a7893b43489f2e73e71f6a3792`，Node 连接本机 WS `0.0.0--202608031304`，唯一 Session `ses_fdd50c1bfffecr1wZUiihzisso` 把 `Calculator.add` 从减法修为加法并原生 idle；Node 的受信 profile 独立执行 `mvn -DforkCount=0 test`，`acceptance-report.json` 为 PASSED、exit 0 且 Surefire 产物存在。Service Run completed，Task 先进入 `REVIEW/PENDING`，显式接受后为 `DONE/ACCEPTED`；Service 同 SQLite 重启和 Web production render 前后通过，Service 敏感数据扫描未发现 Session、workspace、原始事件或 conversation。
- 真实 Gate 还暴露并修复两处边界：WS endpoint 消失时 SSE failure 曾扇出为每分钟数千事件，现为单计划、指数退避和告警去重；status map 短暂缺少正在 busy 的 Session 曾被误判 idle，现必须等待显式 idle 或最终 assistant message 的 completed/non-tool-calls 证据。另确认本机 WS 的 `bash.proxy.execute.requested` 依赖编辑器 terminal proxy，不能作为 headless Node 验收路径；测试和业务验收由 Node 本机 profile 执行。默认 profiles 仍为空，未登记 profile 继续 UNKNOWN。候选代码和文档冻结为 `v0.5.0-trusted-loop-rc1`；没有部署、安装或生产写入。Task accept 中的 `0000000`/`example.test` 只模拟本地 E2E 人工接受，不宣称 canary 自身创建了真实 commit/PR 交付。

- 中间阶段记录（已被前述可信业务闭环取代）：候选工作树按 ADR-003 重建 Node→WS，删除旧 `ws run --format json`、固定三 Turn、`lingfeng.terminal`、摘要 JSON 扫描和 CLI 进程控制，改为显式 loopback endpoint/version Gate、OpenCode HTTP/SSE、一次 Mission prompt、原生 permission/question/abort、Session server/version/workspace 绑定和独立 AcceptanceEvaluator。该阶段曾因默认 `FailClosedAcceptanceEvaluator` 返回 `UNKNOWN` 而不能让完整业务 Run 自动 completed；随后已增加 Node 本机受信 acceptance profile，并由前述 `lingfeng-control-loop-e2e-QTDYy1` 真实闭环证明。此阶段的 question、completed Session reattach、模型 Gate、endpoint fail-closed 和 abort 竞态证据仍有效；连续真实 Run、真实 permission、活动 Run 重启恢复和受控 SSE 故障恢复仍是后续稳定性 Gate。详细证据见 `testing/e2e-native-opencode-real-ws.md`。

## 重建前 main 基线与历史验证

以下记录用于解释 `main@a319dc8` 的来源和旧 CLI 协议曾暴露的问题；它们不再描述 ADR-003 工作树的执行路径。

- `DF-0.3-control-loop` 已通过 PR #21 合并；真实 WS/Node 收口已通过 PR #22 合并到 `main@510e4dc0cb14798f5c8144676b796dbc6fade69e`。Client API v2、Node Protocol v2、Service/Node/Web v2、出站 HTTPS 控制环、会话式 Runtime SPI、Interaction/通知闭环均已编码；当前源码树不存在旧 API controller/DTO/client/worker/config switch、旧 OpenAPI 或 lint 入口。尚未部署、安装或生产验收，不属于当前线上运行能力。
- 本地验证使用 OpenJDK `21.0.12.1`：Service 5 项、Node 33 项、Web 32 项测试全部通过；根 Maven reactor package、两份 v2 OpenAPI strict lint、26 份 fixtures、Web lint/test/build 通过。最终 `E2E-FLOW` 证据目录为 `lingfeng-control-loop-e2e-XKKaVB`，使用真实 Service/Node JAR、fake session Runtime 和 Web production Worker 完成 3 Turn、Service 真实中断、outbox 恢复重放和 digest 匹配的 `SUCCEEDED/PASSED`；`E2E-NOTIFY` 完成通知投递、fake Hermes resolve、Node durable ACK、同一 Session 恢复和重复 delivery/resolve/ACK 幂等。详细证据见 `testing/e2e-v0.3-control-loop.md`。
- 当前 `codex/df-0.4-real-workflow-projection` 分支以 `main@510e4dc0` 为可信起点，包含 WS workspace 绑定 `2380ec8`、真实开发投影/重启验证 `1f312c9`、开发观察窗 `b4d5c02` 和真实 terminal 适配 `2a8d23b`；这些提交均已推送远端分支。
- `main@cd2b8a02f091f61d454f6f886867af4dad83e7a2` 已冻结为 `v0.2.0-mvp1-rc1`，包含 Java Service、Java Node 与 Sites Web；旧 Python v0.1 仍以 `legacy-reference` 共存，尚未到删除 Gate。
- Service 与 Node 已统一为 Java 21、Spring Boot 3.5.5，并由根 Maven reactor 构建；Web 使用 Sites 官方 Vinext/React/TypeScript 骨架。
- v0.2 冻结基线及其旧协议仍可从不可变 `v0.2.0-mvp1-rc1` tag 审计，但不再位于当前源码树，也不构成兼容承诺。
- 本机组合 E2E-1 已使用临时自签 HTTPS、真实 Service JAR、真实 Node JAR 和 fake WS 进程完成：WorkItem/Mission/Run 进入 completed，Node 五类证据齐全，Service 敏感值扫描通过，Service 重启后状态仍可恢复，Web 生产构建能只读显示一致状态且不渲染 Node-only 值。证据说明见 `doc/testing/e2e-1-fake.md`。
- 最终代码已启动真实 Service JAR、真实 Node JAR 与真实 WS `0.0.0--202608171122` 完成一个可人工复算的 shipment-count 任务：`run_339aaccefe294a59bb6983e254843634` 在唯一 Session `ses_fe27959baffe0jHllmrR2WwEc0` 中完成 3/3 Turn，terminal 为 `SUCCEEDED/PASSED`，digest 匹配，Service 投影 `completed`，Node 原始事件 3465 bytes、stderr 0 bytes。`ws providers list` 为 0 credential、`ws models` 空不能作为默认 agent/model 不可用判据；直接 `ws run` 和真实控制环均已证明本机 WS 可用。
- v2 WS Adapter 已移除旧一次性 Runtime 类型；Java 子进程启动后显式关闭 stdin，避免 WS 等待 EOF；最终 Turn 严格先交付 `TurnFinished` 再交付 terminal；提示使用真实任务语义并明确协议枚举。单测和真实证据证明同一观测到的 WS Session ID 可通过 `--session` 连续 3 Turn；它不宣称跨 Node 进程恢复或 durable Interaction，能力不足时继续 fail closed。
- Service→Node→真实 WS 已完成一次真实代码开发全链路：Run `run_8de0fc38500a48bba303823bd56f7254` 在唯一 Session `ses_fe2162583ffeFqjuc64UZdkvut` 中完成 3/3 Turn，WS 修改 Node adapter、补 fail-closed 测试、运行测试与 fake E2E、创建并推送提交 `2a8d23b`；terminal 为 `SUCCEEDED/PASSED`，Service 投影 completed。production Web Worker 在 Service 同端口、同 SQLite 重启前后均显示相同 WorkItem 已完成，Service SQLite/WAL 未发现 Session、workspace 或 Node 原始证据。
- 真实开发前两次未闭环尝试分别暴露 workspace 工具 cwd 回落、10 分钟观察窗不足和“摘要 + terminal JSON”解析问题；失败目录与 uncertain/running 状态均保留，未伪报成功。对应修复已由后续真实 Run 验证。
- 下一轮不再以 `delegate-to-ws`/Bridge 为执行设计；只使用 Service WorkItem → Node 出站 poll → Node 直接启动本机 WS。先完成 2 个不预置实现 diff 的小型真实开发 canary，再考虑真实 Service 中断、Node 重启或 Interaction。真实 Interaction、checkpoint/resume、Node 跨进程恢复和真实 WS 执行期间的单变量故障注入仍未验证。
- 历史 MVP-A 曾证明 Hermes → SSH worker-stream → office-pc Node → 真实 WS 的无工具链路；它不替代当前 DF-0.3 WS Gate。
- MVP-B 未证明：旧实现会丢失 Mission 合同、猜测裸审批对象，并把 Runtime 正常退出误判为业务完成。
- 旧 WS 文件任务和本轮首次真实开发均证明仅设置子进程 cwd 不足以约束工具；Node prompt 现显式绑定绝对 workspace。最终真实 Run 的所有 file/shell 工具均在授权仓库内，且只修改 Mission 授权文件。
- 个人服务器已以独立 user systemd service 运行 Service，监听 `127.0.0.1:18080`，SQLite 权限为 `0600`，停机备份和重启恢复已验证；公网目前使用会在重启后变化的 Cloudflare Quick Tunnel，不是稳定生产入口。
- 当前电脑仍安装冻结版本 Node JAR、独立 Java 21 Runtime 和 `Lingfeng Workbench Node v0.2` 计划任务；本轮开始时既有 PID 29856 正在运行且从未被验证命令指向或修改，但它在验证期间自行退出，最终任务状态为 Ready、无安装版 Node Java 进程。本轮未擅自重启。此前 Service 已看到 `office-pc` 持续心跳；旧 Python Node 任务仅禁用，未删除。公司电脑没有承担 workbench 源码开发。
- 现有 Lingfeng Workbench Private Site 已发布版本 4，仍为 owner、custom access、唯一允许用户、0 个外部访客，D1/R2 均为 `null`；服务端只读 credential 与 Service URL 已更新。匿名访问返回 401，现有 bypass 测试返回 403，因此已证明身份层 fail-closed，但尚未完成登录用户的页面级生产 E2E。
- CI/CD 已建立仓库级 Java、Web、v2 OpenAPI、Windows Wrapper Gate，以及只从不可变 `v*` 标签生成两个 JAR 和 SHA-256 的 GitHub Release 流程。CI/Release 不持有生产凭证，也不自动部署任何环境。
- Draft PR #1/#7/#8/#9 已标注被当前设计取代并关闭；其头分支、重复文档分支和已合入的三模块开发分支均已按 exact SHA 登记后删除。远端只保留 `main`，冻结恢复点为 `v0.2.0-mvp1-rc1`。
- 尚未通过连续三个完整 green 的真实开发 Run、真实恢复/Interaction、登录用户 Sites 页面和完整生产形态 smoke；因此不删除旧 Python、旧 D1 或其它受 Gate 保护的遗留资产。

详细旧证据保存在 `doc/history/v0.1-rescue/` 与 Git 历史中。
