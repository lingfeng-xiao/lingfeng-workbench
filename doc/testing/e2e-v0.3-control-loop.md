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
- Runtime：Node 模块的 deterministic `fake-session` Adapter，完整 Session/Turn/原始证据只写 Node 临时目录；
- Hermes：集成 harness 只调用冻结的 Notification/Interaction Client API，不发送真实微信；
- Web：实际 `vinext build` 的 `dist/server/index.js`，使用双 Sites 身份 header 和 server-only read token 请求真实 Service；
- 数据：两个场景各使用独立临时 Service SQLite、Node SQLite、workspace 和日志，不连接生产系统。

可重复 harness：`integration/control-loop-e2e.mjs`。本次证据目录：

```text
D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-control-loop-e2e-N9Vp28
```

## E2E-FLOW

精确标识：

```text
workItemId    wi_46280ab6b5374fe698eac0294de9651d
runId         run_50438e6f49a14831858850b75e0e9d3d
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
workItemId       wi_2f0ffc6188c14fb9ba2fa2a39a285436
runId            run_62c5a9c6e498439ea9b8a94d2a74dda3
interactionId    int_001
notificationId   ntf_d1bca6fc3c95475cb9bd29c7d7e39094
responseCommand  cmd_1a141356df7e49949191cfdf96b5190b
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
Node: 28 tests, 0 failure/error (v2 only, 9 suites)
Web: 32 tests passed; lint passed; production build 5/5
OpenAPI: v2 Client + v2 Node strict lint passed; old contracts/lint entries absent
v2 fixtures: 26 positive/negative fixtures passed strict schema/boundary validation
Contract tool audit: 0 vulnerabilities
Web production dependency audit: 0 vulnerabilities
```

故障路径由模块测试与组合 harness 共同覆盖：Service/Node 重启、command/response ACK 前后持久化、重复 command/event/ACK/resolve/report、取消与可信终态竞争、错误 digest/checkpoint/Node/state、UNKNOWN fail closed、Runtime handle 丢失、Service 断网 outbox、401/403/407、TLS/proxy 分类和敏感数据扫描。

构建产物：

```text
Service JAR  47,583,942 bytes  SHA-256 244E6D4A69392BD75A367D3A32E436E73DC004D75FFB4AD6407A005422BF9F4D
Node JAR     27,919,691 bytes  SHA-256 A4795A348FF6F7C27521828718FAFF931861F07DF5CC2931704D3E70EA400D70
Web worker      182,531 bytes  SHA-256 30CAFD997EACAEC2A1B565E906BEBF956DD87BAB97CE376AB918AD501F5B4450
```

## 真实 WS 受控尝试

可重复命令：`node integration/control-loop-e2e.mjs --real-ws`。本次使用真实 Service JAR、真实 Node JAR、真实 `ws.cmd`，Hermes 保持本地受控边界且未发送微信；证据目录：

```text
D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-control-loop-e2e-MfcIG0
workItemId    wi_478628affdb444929f0ac2d1e2f3c06a
runId         run_d45a6f45e87a476183cf5e221a701411
missionDigest b1f157339097c9a97a263207f27c44ac5c21d868c3c328b3f5a7a5c31b17731b
```

Service 创建并投递 START_RUN，Node 持久化命令、建立本地 Session 控制记录并提交 Turn 1。30 秒有界观察内，真实 WS 没有输出 Session ID、JSON 事件、stderr 或终态：`submittedTurns=1`、`finishedTurns=0`、`runtimeEventsBytes=0`、`runtimeStderrBytes=0`，Service Run 保持 `running`。harness 随后只终止本次 Node/WS/Service 进程树；最终审计无本轮残留进程。既有安装版 Node PID 29856 从未被本轮命令指向或修改，但在验证期间自行退出，最终计划任务状态为 Ready；本轮未擅自重启。

同轮动态检查：WS 版本 `0.0.0--202608171122`，`ws providers list` 为 0 credential，`ws models` 无条目。因此真实 Session 创建、同 Session 多 Turn、可信完成、digest/checkpoint/acceptance、Interaction 和活动 Run 重启恢复均未验证；不存在真实 Interaction ID。该结果是带证据的外部 Runtime 阻塞，不是成功。

## 运行中修复

- 根 reactor 首次并发运行暴露 Node SQLite `SQLITE_BUSY`：ServiceConnectionLoop 落盘取消命令与 RunSupervisor 写事件竞争。`ControlLoopStore` 的所有写入口现以单写锁串行化；Node 28 项和根 reactor 重跑通过。
- 最终复验曾暴露 Mockito inline mock maker 在本机 JDK 21 上动态 attach 失败；Service 测试 JVM 现由 Maven Surefire 预加载 Mockito agent，不再依赖偶发的运行时自附加。无额外 attach 参数的 Service 5 项测试已重跑通过。
- harness 的 Java 版本采集现跳过 `JAVA_TOOL_OPTIONS` 提示并提取真实 `java -version` 行；Windows 清理按本次子进程 PID 终止完整进程树，避免孤儿 WS。
- Windows 的 Java `ProcessBuilder` 不会把裸 `ws` 解析为批处理入口；真实模式现通过 `where.exe ws.cmd` 解析绝对路径，同时保留 `WORKBENCH_WS_EXECUTABLE` 显式覆盖。修复后 Node preflight、注册和 Turn 1 提交均成功。
- WS Adapter 不再使用 `--dir`，而是设置子进程工作目录；同进程多 Turn 使用真实观测到的 Session ID 和 `--session`，但没有证据时不宣称跨进程恢复。

## 尚未完成与 Gate

- fake Runtime/Hermes 只证明冻结边界，不等同于真实 WS、真实 Hermes 或真实微信；
- 真实 WS 当前 credential/model 阻塞仍存在；上述真实 Run 没有伪造 Session、Interaction 或成功终态；
- 未连接真实企业代理，proxy/TLS/401/403/407 使用独立边界测试；
- Client API v2 没有公开取消 endpoint，`CANCEL_RUN` 仅由内部控制面测试生成；
- 未执行 commit、merge、push、tag、release、服务器部署、Node 安装、Sites 发布、生产凭证操作、真实微信发送或遗留删除；
- 旧 Python、旧 D1、公司电脑源码开发边界和所有后续生产 Gate 保持不变。
