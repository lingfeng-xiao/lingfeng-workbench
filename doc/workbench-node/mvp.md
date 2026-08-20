---
status: frozen-superseded
authority: main
source_ref: architecture-v1
owner: workbench-node
superseded_by: detailed-design.md
last_verified: 2026-08-19
---

# MVP-N1

> 状态说明：本文保留 v0.2/N1 范围和验收事实，不作为 v0.3 新实现输入；目标范围见 `detailed-design.md` 和开发计划。

## 运行基线

- Java 21、Spring Boot 3.5、Maven，可执行 JAR。
- `WebApplicationType.NONE`，不开放业务端口。
- Windows 是首个承诺平台；配置通过环境变量或外部 Spring 配置提供。
- Service URL 必须为 HTTPS；每个 Node 使用独立的高熵 bearer credential。

## 本地状态

状态目录包含 `node.db` 和 `runs/<runId>/`。每个 Run 目录固定包含：

- `mission.json`
- `normalized-events.ndjson`
- `runtime-events.ndjson`
- `runtime-stderr.log`
- `result.md`

SQLite 使用外键、WAL、同步写和 busy timeout。事件在网络发送前进入 outbox，收到匹配
`requestMessageId` 的 ACK 后才能删除。相同 command/run/digest 的 Assignment 不再次启动 Runtime；
身份冲突则 fail closed。

## E2E-N1 验收

1. fake Service 可观察 hello、heartbeat、poll、accepted、started、progress 和 terminal。
2. fake Runtime 的 `PASSED` 终态形成 `EXECUTION_FINISHED`；缺少终态形成 `FAILED/UNKNOWN`。
3. 网络暂时失败后重启本地 store，outbox 仍能重投并在 ACK 后清除。
4. 重复 Assignment 不产生第二次 Runtime start。
5. 任一 Service payload 均不含 Session ID、本地绝对路径、原始事件或完整结果。
6. 真实 WS 只允许无工具 Mission；文件能力和 Interaction 恢复不属于 MVP-N1。

## 后续 Gate

MVP-N2 才实现 Interaction 四元绑定、响应重投/ACK 和原 Session 恢复。文件工具、沙箱、部署、
公司电脑安装和生产凭证均需要各自独立 Gate，MVP-N1 测试通过不自动授权。
