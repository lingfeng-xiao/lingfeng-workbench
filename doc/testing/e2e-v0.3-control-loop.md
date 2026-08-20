---
status: current-evidence
authority: working-tree-evidence
source_ref: DF-0.3-control-loop-local-e2e
owner: integration
superseded_by: null
last_verified: 2026-08-20
---

# DF-0.3 Control Loop 本地组合证据

## 验证形态

- Java：Azul Zulu OpenJDK `21.0.12.1` LTS；
- Service：真实可执行 JAR，通过临时自签 PKCS12 在随机 loopback 端口提供 HTTPS；
- Node：真实非 Web 可执行 JAR，通过临时 truststore 只做出站 HTTPS；
- Runtime：fake Gate 使用 Node 模块的 deterministic `fake-session` Adapter；真实 Gate 使用本机 WS `0.0.0--202608171122`；完整 Session/Turn/原始证据只写 Node 临时目录；
- Hermes：集成 harness 只调用冻结的 Notification/Interaction Client API，不发送真实微信；
- Web：实际 `vinext build` 的 `dist/server/index.js`，使用双 Sites 身份 header 和 server-only read token 请求真实 Service；
- 数据：两个场景各使用独立临时 Service SQLite、Node SQLite、workspace 和日志，不连接生产系统。

可重复 harness：`integration/control-loop-e2e.mjs`。本次证据目录：

```text
D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-control-loop-e2e-Hbx3cR
```

## E2E-FLOW

精确标识：

```text
workItemId    wi_cf3527973dd540e5b4d7b19921235ff7
runId         run_1713b4950b414d389f623e427046c743
missionDigest 6f586a2a8bcfadbe50549ba59beaef7c8ac336d3b8b836919d65e2135cb19008
```

结果：

- Node 拉取并持久化 `START_RUN` 后只打开一个 Agent Session；
- fake Runtime 完成 3 个连续 Turn；
- Run 进入 running 后真实停止 Service 进程，Runtime 在网络中断期间继续，Node SQLite 的 finished Turn 达到 3 且 outbox 非空；
- 使用同一 Service SQLite 和同一 HTTPS 端口重启，Node 顺序重放 outbox，最终 `completed`；
- 终态 result 为 `Three deterministic turns passed the frozen acceptance checks`，Mission 快照 digest 与 Service 创建响应完全相同；
- outbox 最终清空，本地 `control_agent_session=1`、finished Turn `=3`；
- 再次重启 Service 后仍查询到 completed；
- Web production Worker 显示该 WorkItem 已完成；
- Node Run 目录包含 `mission.json`、`control-commands.ndjson`、`normalized-events.ndjson`、`runtime-events.ndjson`、`runtime-stderr.log`、`conversation.ndjson`、`checkpoints/` 和 `result.md`；
- Service SQLite/WAL 字节扫描未出现 workspace 绝对路径、fake Session handle 或 Node runtime evidence 文件名。

## E2E-NOTIFY

精确标识：

```text
workItemId       wi_8ef0886b8f5c4e37a7960e18d69e7033
runId            run_9fe6e8d3e45a4b0bacd2c050426a3014
interactionId    int_001
notificationId   ntf_5cb347a049944ab8856060d56f5fb9e2
responseCommand  cmd_b27906c0af394c59a31eeffbae8f6183
```

结果：

- Turn 1 请求 Interaction，Node 持久化 checkpoint 和原 Session handle，Run 进入 waiting；
- Service 同事务创建 Interaction 与 `INTERACTION_REQUIRED` Notification；
- fake Hermes poll 得到通知并 report `DELIVERED`；重复 delivery report 返回 `duplicate=true`；
- 错 mission digest、错 checkpoint 的 resolve 均返回 409；
- 合法 resolve 创建唯一响应命令；同 Idempotency-Key 重放返回同 commandId 和 `duplicate=true`；
- Node 先持久化响应命令与 `COMMAND_STORED`，再交给原 Session；Service 最终看到 Interaction `consumed`；
- Agent Session 记录数始终为 1，`PROVIDE_INTERACTION_RESPONSE` 本地命令记录数为 1；
- 重放同一 `COMMAND_STORED` ACK 返回 `duplicate=true`，错误 Node credential 绑定返回 403；
- consumed 后再次 resolve 返回 409，不产生第二响应；
- 同一 Session 继续到 Turn 3，最终 `completed`，Web production Worker 显示 Interaction 已消费；
- Service SQLite/WAL 扫描未出现 workspace 绝对路径、Session handle 或完整 conversation 文件名。

## 独立与全量 Gate

```text
Root Maven reactor: BUILD SUCCESS
Service: 5 tests, 0 failure/error (v2 only)
Node: 29 tests, 0 failure/error (v2 only, 9 suites)
Web: 32 tests passed; lint passed; production build 5/5
OpenAPI: v2 Client + v2 Node strict lint passed; old contracts/lint entries absent
v2 fixtures: 26 positive/negative fixtures passed strict schema/boundary validation
Contract tool audit: 0 vulnerabilities
Web production dependency audit: 0 vulnerabilities
```

故障路径由模块测试与组合 harness 共同覆盖：Service/Node 重启、command/response ACK 前后持久化、重复 command/event/ACK/resolve/report、取消与可信终态竞争、错误 digest/checkpoint/Node/state、UNKNOWN fail closed、Runtime handle 丢失、Service 断网 outbox、401/403/407、TLS/proxy 分类和敏感数据扫描。

构建产物：

```text
Service JAR  47,583,942 bytes  SHA-256 A7A2141FAD2C7400FF3F2ADF4238ADE8BAEDD64263534A944F8A99936407F9B7
Node JAR     27,920,333 bytes  SHA-256 943FA8D53DA8D81BA49682CB0BD4FC25E65E3D5A03258DC8C83DD9CB4F6B7AEA
Web worker      182,531 bytes  SHA-256 99C691DB94F4078A5F95C427D6A7789A1A17B0F2D986374BAE6CAE81AA5C9E6B
```

## 真实 WS 最小业务闭环

可重复命令：`node integration/control-loop-e2e.mjs --real-ws`，默认观察窗为 120 秒，可用 `WORKBENCH_REAL_WS_TIMEOUT_MS` 在 30 秒至 10 分钟内覆盖。本次使用上述最终 Service/Node JAR 和真实 `ws.cmd`；Hermes 保持本地受控边界且未发送微信。最终证据目录：

```text
D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-control-loop-e2e-tOH2Kb
workItemId    wi_1a03fd2e61c846988a7195a4517d3242
runId         run_339aaccefe294a59bb6983e254843634
missionDigest 42d413af4afd7b78b04314ce5826304dd6d57b060daf06a11641acfede07b3f3
wsSessionId   ses_fe27959baffe0jHllmrR2WwEc0
```

真实任务为对 shipment counts `17, 23, 40` 计算并复核数量、总和和算术平均数。Service 创建并投递 `START_RUN`，Node 持久化命令并启动真实 WS；9 个原始 WS 事件只出现一个真实 Session ID。结果为：

- `submittedTurns=3`、`finishedTurns=3`、Service 投影 `status=completed`；
- terminal 为 `SUCCEEDED/PASSED`，digest 与创建响应完全一致；
- result 为 `count=3`、`total=80`、`mean=26.6666666667 (≈26.67)`，可人工复算；
- Node evidence 目录含 mission、commands、normalized/raw events、stderr、conversation、checkpoint 目录和 result；
- `runtimeEventsBytes=3465`、`runtimeStderrBytes=0`，只出现一个 terminal；
- harness 退出后无本轮 Service、Node 或 WS 子进程残留。

直接命令 `ws.cmd run --format json` 同样成功返回结构化事件和真实 Session。`ws providers list` 的 0 credential 与 `ws models` 的空输出不能作为默认 agent/model 不可用的判据；真实 `run` smoke 和控制环结果才是本机当前可用性的证据。

本轮没有完成真实 Interaction、真实 checkpoint/resume、Node 跨进程 Session 恢复、Service/Node 故障注入或 Service/Web 重启持久化。这些能力仍不得由 fake 证据外推。

## 运行中修复

- 根 reactor 首次并发运行暴露 Node SQLite `SQLITE_BUSY`：ServiceConnectionLoop 落盘取消命令与 RunSupervisor 写事件竞争。`ControlLoopStore` 的所有写入口现以单写锁串行化；Node 29 项和根 reactor 重跑通过。
- 最终复验曾暴露 Mockito inline mock maker 在本机 JDK 21 上动态 attach 失败；Service 测试 JVM 现由 Maven Surefire 预加载 Mockito agent，不再依赖偶发的运行时自附加。无额外 attach 参数的 Service 5 项测试已重跑通过。
- harness 的 Java 版本采集现跳过 `JAVA_TOOL_OPTIONS` 提示并提取真实 `java -version` 行；Windows 清理按本次子进程 PID 终止完整进程树，避免孤儿 WS。
- Windows 的 Java `ProcessBuilder` 不会把裸 `ws` 解析为批处理入口；真实模式现通过 `where.exe ws.cmd` 解析绝对路径，同时保留 `WORKBENCH_WS_EXECUTABLE` 显式覆盖。修复后 Node preflight、注册和 Turn 1 提交均成功。
- Java 启动的 WS 曾因子进程 stdin pipe 保持打开而等待 EOF、零输出；probe 和 Turn 执行现在启动后立即关闭 stdin，关闭失败时终止子进程并 fail closed。测试固定验证 stdin 已关闭。
- WS terminal 曾先于最终 `TurnFinished` 到达，使 Run 进入 terminal 后忽略 Turn 完成；Session Adapter 现在实时转发普通事件、暂存 terminal，并严格按 `TurnFinished` 后 terminal 的顺序交付。
- 初始合成 Mission 文案被真实 WS 正确识别为 scripted injection；改为可人工复算的真实小任务后，WS 又自然输出 `completed/passed` 而非协议枚举。通用提示现使用自然任务语义，并明确列出 `SUCCEEDED/FAILED/INTERRUPTED/UNKNOWN` 与 `PASSED/FAILED/UNKNOWN`；解释器仍严格拒绝不支持的状态，没有放宽合同或硬编码业务答案。
- WS Adapter 不再使用 `--dir`，而是设置子进程工作目录；同进程多 Turn 使用真实观测到的 Session ID 和 `--session`，但没有证据时不宣称跨进程恢复。

## 尚未完成与 Gate

- fake Runtime/Hermes 只证明冻结边界，不等同于真实 WS、真实 Hermes 或真实微信；
- R1 三个固定样本目前只完成“数字汇总”1 类，最终适配后已有连续 3 个成功 Run；短文本验收项、约束清单和三类样本 Gate 尚未完成；
- 真实 Interaction、checkpoint/resume、Node 跨进程恢复、真实故障注入和 Service/Web 重启持久化尚未验证；
- 未连接真实企业代理，proxy/TLS/401/403/407 使用独立边界测试；
- Client API v2 没有公开取消 endpoint，`CANCEL_RUN` 仅由内部控制面测试生成；
- 本地验证未执行服务器部署、Node 安装、Sites 发布、生产凭证操作、真实微信发送或遗留删除；
- 旧 Python、旧 D1、公司电脑源码开发边界和所有后续生产 Gate 保持不变。
