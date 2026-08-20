---
status: frozen-superseded
authority: main
source_ref: service-mvp-s1
owner: workbench-service
superseded_by: detailed-design.md
last_verified: 2026-08-19
---

# MVP-S1

> 状态说明：本文保留 v0.2/S1 范围和验收事实，不作为 v0.3 新实现输入；目标范围见 `detailed-design.md` 和开发计划。

## 包含

- Java 21、Spring Boot 3.5、Spring JDBC、SQLite、Liquibase formatted SQL。
- 原子创建 WorkItem 与首个 Mission；WorkItem/Mission/Run/Node 与空 Interaction 的只读查询。
- Node hello、heartbeat、poll、assignment ACK、accepted、started、progress 和 terminal event。
- Hermes 创建/读取、Sites 只读、Node 绑定凭证。
- 64 KiB 请求限制、800 字符摘要、严格未知字段拒绝、版本/ID 校验和短错误响应。
- SQLite 外键、WAL、FULL synchronous、busy timeout 与单写连接池。

## 不包含

- WorkItem 编辑、追加 Mission、重试建模、跨 Node 迁移、Interaction 处理或历史数据导入。
- Runtime、工作区、文件、产物、日志和对话存储。
- TLS 证书管理、凭证签发、部署和外部资源清理。

## MVP-S1 验收

1. Hermes scope 原子创建任务，重复相同幂等请求返回同一结果。
2. Sites scope 可读取但不能创建。
3. 绑定 Node 注册、领取并完成一次无工具任务；未 ACK assignment 可重投。
4. 只有显式 `PASSED` 完成；`FAILED`、`UNKNOWN`、Runtime 失败和中断不完成。
5. Service 响应与数据库不出现 Session、绝对路径、原始事件或完整结果。
6. 非法状态、错误 Node/digest、重复 messageId 冲突、未知字段、超长摘要和超限 payload 均 fail closed。
