---
status: authoritative
authority: DF-0.3-control-loop
source_ref: workflow-v2
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# 业务流程与两个闭环

## 1. 权威关系

Service 是任务控制状态的权威来源；工作电脑 `SPM/xlf` 是完整过程资料和产物的本地权威。两者通过稳定 ID 和短 checkpoint 对齐，但不复制完整内容。

- WorkItem：一个需要交付的 SPM 事项；
- Mission：本次执行不可静默修改的合同；
- Run：Mission 的一次执行；
- Agent Session：Node 本地的一段持续 Agent 会话；
- message：OpenCode 会话中的原生输入与输出；
- Interaction：需要可信客户端或人的精确输入；
- Notification：Service 产生、Hermes 投递的消息意图，不是新的业务聚合。

## 2. 工作流闭环

```text
任务创建
  -> Mission 合同冻结
  -> Service 为目标 Node 建立 START_RUN 命令
  -> Node pull、落盘、ACK
  -> Node 打开 Agent Session
  -> Node 异步提交一次 Mission prompt
  -> Agent 在原生 Session 内执行
       -> 理解任务和读取 xlf
       -> 冻结源码与需求上下文
       -> 实现
       -> Maven/Newman/专项验证
       -> 生成本地报告
  -> 必要时 WAITING_INTERACTION 并恢复同一 Session
  -> OpenCode Session 收敛到 idle，Node 对账 status/messages/evidence
  -> 独立 AcceptanceEvaluator 核验 Mission
  -> Node 本地保存完整证据并上报短终态
  -> Service 依据 PASSED/FAILED/UNKNOWN 汇总状态
  -> Web 和 Hermes 读取一致结果
```

`executionProfile=spm-change-v1` 由 Runtime Adapter/Agent 解释。Service 和 Node 只看到阶段代码和短摘要，不固化 `xlf` 的目录结构，也不把旧文件夹状态反向推断成业务状态。

建议的本地阶段投影为：

```text
CONTRACT_REVIEW
CONTEXT_FREEZE
IMPLEMENTATION
BUILD_VALIDATION
API_VALIDATION
REPORTING
```

阶段只是观察投影，不是 Service 完成条件。不同 Runtime 可以产生更多本地子阶段，但上报 Service 时必须映射成短、稳定的 `phaseCode`。

## 3. 24 小时运行规则

- Node 随 Windows 启动，网络循环与 Agent 执行线程隔离；
- 网络断开不取消已授权且正在运行的 Agent；事件进入本地 outbox；
- Node 重启后先读取本地 Run、Session handle、命令日志和 outbox；
- Adapter 明确声明可恢复且 handle 匹配时才恢复原 Session；
- 无法证明原 Session 身份时上报 `uncertain`，不自动再开一个 Session；
- Interaction 等待期间保留 Session/checkpoint，不把请求解释为失败；
- 同一工作区首轮只允许一个活动 Run，避免文件副作用竞争；
- 取消、idle/验收结果和 Interaction 响应都进入 RunSupervisor 的单一串行事件流。

## 4. 通知与审批闭环

```text
Run/Interaction 发生需要通知的状态转换
  -> Service 同事务写 notification outbox
  -> Hermes 使用 scoped credential 主动 pull
  -> Hermes 按 notificationId 幂等发送微信
  -> Hermes 回报 DELIVERED 或 FAILED
  -> 用户回复批准/拒绝/输入
  -> Hermes 调用 Interaction resolve
  -> Service 持久化响应并建立 PROVIDE_INTERACTION_RESPONSE 命令
  -> Node pull，先本地持久化，再 ACK
  -> Node 投递给同一 Agent Session
  -> Agent 继续执行
  -> Node 上报 RESPONSE_CONSUMED 和后续状态
```

Interaction 精确绑定：

```text
interactionId + runId + checkpointId + missionDigest + targetNodeId
```

响应还包含 `decision`、最多 800 字符的 `responseSummary`、`resolvedBy` 和 `resolvedAt`。任一绑定、状态或目标 Node 不匹配都 fail closed，且不得消费响应。

Hermes 负责微信用户映射、消息格式和渠道重试；Service 只保存逻辑目标 `owner` 和投递结果，不保存微信 token、联系人详情或会话内容。Hermes ACK 不能代表用户已批准，Interaction resolve 也不能代表 Node 已消费；这三步必须分别记录。

## 5. 通知类型

首轮只产生高价值通知：

- `INTERACTION_REQUIRED`；
- `RUN_COMPLETED`；
- `RUN_FAILED`；
- `RUN_UNCERTAIN`；
- `NODE_OFFLINE_WITH_ACTIVE_RUN`。

普通 progress 不发送微信，避免高频噪声。相同 `eventId + notificationType + target` 只产生一个通知；离线告警需要冷却时间，恢复后可产生一条恢复时间线，但首轮不要求微信通知。

## 6. Web 观察闭环

Web 只读展示：WorkItem 汇总、当前 Mission/Run 状态、最新阶段和进度、等待中的 Interaction、Node 在线情况以及重要通知的投递投影。Web 不读取 xlf、完整报告或 Runtime 对话，也不提供批准按钮。

Web 看到的是 Service 已持久化的状态；Node 尚未同步的本地进度明确显示为“最后同步于”，不得伪装为实时流。

## 7. 两个端到端验收

### E2E-FLOW

创建 SPM Mission，Node 通过 fake Runtime 向一个 Session 提交一次 prompt，中间断开 Service 网络后继续运行并重放事件，再由独立 fake AcceptanceEvaluator 产生 `PASSED`。Service 和 Web 显示 completed；完整对话、Session、路径、diff 和报告只存在 Node。

### E2E-NOTIFY

fake Runtime 请求 Interaction，Node 保留 Session；Service 创建 Interaction 和通知；fake Hermes 拉取并确认投递，再用精确绑定 resolve；Service 重投直到 Node 本地保存并 ACK；同一 Session 恢复并完成。重复微信回复、重复 resolve、重复命令和重复 ACK 都不产生第二次副作用。
