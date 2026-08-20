---
status: authoritative
authority: DF-0.3-control-loop
source_ref: plan-v0.3-node
owner: workbench-node
superseded_by: null
last_verified: 2026-08-20
---

# Node 开发任务 N2

## N2-001：异步运行骨架

- 依赖：A-002、A-003；
- 分离 ServiceConnectionLoop、RunSupervisor 和 Runtime executor；
- 保持 `WebApplicationType.NONE` 和单活动 Run；
- 验收：fake Runtime 阻塞时 heartbeat、poll、cancel 仍继续。

## N2-002：本地事件与命令箱

- 前向升级 node.db，加入 received command、local sequence、session、turn、interaction binding；
- START_RUN 先落盘再 COMMAND_STORED；
- outbox 继续保持先写后发和 ACK 后删除；
- 验收：进程在每个写入点崩溃并重启都不重复打开 Runtime。

## N2-003：会话式 Runtime SPI

- 实现异步 openSession、submitTurn、interaction response、checkpoint、pause/resume/cancel/inspect/close；
- 建立 Session、Turn 和 normalized event 类型；
- Runtime 专用字段限制在 Adapter；
- 验收：fake Runtime 至少三个 Turn，网络循环不阻塞。

## N2-004：RunSupervisor 状态机

- 串行处理 command、Runtime event、timer 和取消；
- 实现本地 Run/Session/Turn 三层状态；
- first durable terminal wins；
- 验收：取消/终态竞争、迟到事件、重复 command 和 busy Node 表驱动测试。

## N2-005：Interaction 暂停与恢复

- InteractionRequested 保存 checkpoint/handle，不 cancel；
- 精确响应先落盘 ACK，再投递原 Session；
- Adapter 接受后上报 consumed；
- 验收：错五元绑定 fail closed，重复响应只消费一次，Node 重启后继续等待或恢复。

## N2-006：断网和进程恢复

- 启动时先恢复 outbox 和未终结 Run；
- inspect/resume 原 Session，无法证明身份则 uncertain；
- Service 离线期间允许已授权 Run 继续；
- 验收：断网、Service 重启、Node 重启、Runtime 丢失和 outbox 重放矩阵。

## N2-007：企业网络支持

- 显式 proxy、truststore、connect/request timeout 和退避配置；
- 启动预检区分 DNS/proxy/TLS/auth/protocol/runtime；
- 日志 secret redaction；
- 验收：fake proxy、证书失败、407、401/403、Service 恢复测试，Node 无监听业务端口。

## N2-008：WS Adapter v2

- 先把现有 WS 逻辑适配新 SPI，不把 core 绑定 CLI；
- 能力不足时明确报告，不伪造 Session resume；
- 保留结构化 PASSED/digest 终态规则；
- 验收：fake WS 的多 Turn/Interaction/缺终态/错误 digest；真实 WS 另立 Gate。

## N2-009：本地证据与泄漏 Gate

- 扩展 control commands、conversation 和 checkpoints 证据；
- 运行日志轮换和磁盘错误安全失败；
- 扫描所有发往 Service 的 payload；
- 验收：完整本地证据存在，Session、路径、原始事件、diff、完整结果从不外发。

## 模块交付顺序

```text
N2-001 -> N2-002 -> N2-003 -> N2-004
                    N2-005 -> N2-006
N2-001 -> N2-007
N2-003 -> N2-008 -> N2-009
```

N2-007 可与状态机开发并行；N2-008 必须等会话 SPI 稳定后开始。
