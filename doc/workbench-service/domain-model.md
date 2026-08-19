---
status: authoritative
authority: main
source_ref: service-domain-v1
owner: workbench-service
superseded_by: null
last_verified: 2026-08-19
---

# Service 领域模型

## 对象

- **WorkItem**：客户端工作聚合；MVP-S1 创建后不可编辑，一对多拥有 Mission。
- **Mission**：不可静默修改的执行合同。revision 从 1 开始，digest 覆盖目标、验收、副作用授权、目标 Node、workspace reference、Runtime 和 execution profile。
- **Run**：Mission 的一次执行。assignment 是持久化命令，Node 明确确认后停止重投。
- **Node**：执行电脑的服务端投影，只包含显示名、能力和最后心跳。
- **Interaction**：MVP-S1 只提供只读空集合；状态转换在 MVP-S2 定义。

## 状态规则

Mission 从 `pending` 经 `assigned`、`running` 进入终态。Run 从 `assigned` 进入 `running` 后才能接受进度和终态。

只有同时满足以下三个条件，Run、Mission 与 WorkItem 才能进入 `completed`：

1. Node 上报 `EXECUTION_FINISHED`；
2. `runtimeOutcome=SUCCEEDED`；
3. `acceptanceStatus=PASSED`。

`acceptanceStatus=UNKNOWN` 必须进入 `uncertain`，WorkItem 进入 `attention_required`。失败与中断同样不得解释为完成。

## 幂等与一致性

- 创建 API 以 `Idempotency-Key + 请求摘要` 唯一识别；同键不同请求冲突。
- Node 事件以 `messageId + nodeId + eventType + payload digest` 唯一识别；完全相同的重放返回 `duplicate=true`，冲突重用返回 409。
- assignment 在一个数据库事务中创建 Run 并改变 Mission/WorkItem 状态；未 ACK 时持续向同一 Node 重投。
- Node 事件必须同时匹配 WorkItem、Mission、Run、Node 和 Mission digest。
