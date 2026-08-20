---
status: authoritative
authority: DF-0.3-control-loop
source_ref: plan-v0.3-service
owner: workbench-service
superseded_by: null
last_verified: 2026-08-20
---

# Service 开发任务 S2

## S2-001：v2 API 适配骨架

- 依赖：A-001、A-002、A-003；
- 以 v2 controller/DTO 作为唯一 API 映射；
- 从合同 fixtures 做请求/响应测试；
- scope 拆分为 create、resolve、notification pull/report、read、node-bound；
- 验收：未知字段、错误 scope、错误 nodeId、超长和超限 payload 全部拒绝。

## S2-002：前向数据库迁移

- 新增 control command、Interaction、notification delivery 和必要去重/索引；
- 不修改旧 migration，不迁移 Python SQLite 或 Sites D1；
- 测试空库、v0.2 SQLite 基表前向升级、重复启动、SQLite 重启恢复和外键；
- 验收：现有控制数据可前向读取，失败 migration 不留下半状态。

## S2-003：通用 Node 命令箱

- 创建、租用和 ACK `START_RUN / PROVIDE_INTERACTION_RESPONSE / CANCEL_RUN`；
- command payload digest 和 `COMMAND_STORED` 幂等；
- 并发 poll 同一 Node 只得到同一有效命令；
- 验收：未 ACK 持续重投，ACK 后停止，同 ID 异 payload 冲突。

## S2-004：Run v2 状态机

- 支持 phase、progress、waiting、cancelling 和 terminal；
- 实现 PASSED 唯一完成规则、迟到事件和取消竞争；
- 保持 WorkItem/Mission 汇总一致；
- 验收：所有非法转换表驱动测试，UNKNOWN 永不 completed。

## S2-005：Interaction 生命周期

- 接收精确 Interaction，原子切换 waiting；
- resolve 幂等、allowedDecision 校验和五元绑定；
- 创建响应命令，处理 stored/consumed；
- 处理 expired/cancelled；
- 验收：重复 resolve、错 digest、错 checkpoint、错 Node 和迟到响应均无第二次副作用。

## S2-006：Notification outbox

- 在 Interaction、completed、failed、uncertain 和 active-run node-offline 转换中同事务创建；
- 实现 Hermes poll lease、DELIVERED/FAILED 回报、attempt 和 dead letter；
- 使用稳定 dedup key 和离线冷却；
- 验收：业务事务回滚不产生通知，Hermes 断线可重投，重复回报幂等。

## S2-007：只读投影

- 为 Web/Hermes 返回 phase、progress、lastSyncedAt、Interaction 和重要通知状态；
- 丢弃命令 payload 与内部审计主体；
- 验收：v2 列表/详情分页有界，所有响应通过敏感字段扫描。

## S2-008：Service 回归与质量 Gate

- v2 唯一 API/协议全量回归；
- v2 OpenAPI contract test；
- 并发、重启、busy timeout 和事务原子性测试；
- credential scope、64 KiB 和 800 字符测试；
- SQLite/WAL 扫描不得出现 Session、绝对路径、原始事件、diff 或完整结果。

## 模块交付顺序

```text
S2-001 -> S2-002 -> S2-003 -> S2-004
                   S2-005 -> S2-006 -> S2-007 -> S2-008
```

S2-003 与 S2-005 的内部 repository 工作可并行，但状态机合并由 Service owner 串行审查。
