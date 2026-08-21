---
status: authoritative
authority: DF-0.3-control-loop
source_ref: ADR-002
owner: architecture
superseded_by: ADR-003（仅 Node→Runtime 部分）
last_verified: 2026-08-20
---

# ADR-002：出站控制环与 Agent 会话

> ADR-003 已取代本文关于 Runtime-neutral Turn/pause/checkpoint 协议的决定；Service 出站控制环、单 Run/Session、Interaction 精确绑定和数据边界继续有效。

## 背景

工作电脑可能禁止入站访问，且当前 Node 的同步 `start/resume/cancel` SPI 无法表达持续、多 Turn、可暂停和可恢复的 Agent 工作。Interaction 被解释为中断，也会破坏通知审批闭环。

## 决定

- Service 永远不回连 Node；所有命令持久化后由 Node 通过出站 HTTPS 拉取；
- Node 的 ServiceConnectionLoop 与 Runtime 执行异步解耦；
- Node 是本机 Agent 会话控制器，Runtime 是实际执行者；
- 一个 Run 在首轮对应一个 Agent Session，但可以包含多个 Turn；
- Node 与 Runtime 使用会话式 runtime-neutral SPI；
- Interaction 保留原 Session/checkpoint，响应必须精确绑定并经过 Service 重投、Node 落盘 ACK、Runtime 消费确认；
- 完整 Runtime 数据留在工作电脑，Service 只保存短控制投影；
- Service 使用事务性 notification outbox，Hermes 只负责微信投递和协议转换；
- MVP 只支持单活动 Run，不自动创建替代 Session。

## 结果

Service、Node、Web 可以针对版本化合同独立开发；受限网络不会要求工作电脑开放端口；断网和进程重启可以通过本地状态恢复。代价是 Node 必须引入明确的 RunSupervisor、Session/Turn 本地模型和更严格的顺序控制，Service 必须持久化通用命令、Interaction 和通知投递记录。

## 被拒绝方案

- Server 主动访问 Node：不满足企业网络和最小暴露边界；
- 每个 Turn 都创建新 Run：丢失同一 Agent Session 的上下文和审批恢复语义；
- 把完整对话上传 Service：违反最小控制数据边界；
- Interaction 时取消 Runtime：无法形成审批闭环；
- Hermes 直接控制 Node：产生第二个状态机并绕过 Service 审计。
