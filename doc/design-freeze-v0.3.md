---
status: authoritative
authority: DF-0.3-control-loop
source_ref: DF-0.3-control-loop
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# DF-0.3-control-loop 设计冻结

## 冻结声明

`DF-0.3-control-loop` 于 2026-08-20 在文档层冻结，作为 `v0.3` 开发输入。冻结不表示功能已经部署或通过生产验收。当前本地实现与验证事实以 `doc/current-state.md` 和 Client/Node v2 OpenAPI 为准。

## 冻结范围

- 三模块及外部 Hermes/Runtime 边界；
- Service 永不回连 Node 的出站 HTTPS 模型；
- WorkItem、Mission、Run、Interaction、Node 的业务语义；
- Notification 作为事务性投递记录而非业务聚合；
- 一个 Run、一个本地 Agent Session、多个 Turn；
- Node 异步网络循环、RunSupervisor、本地 outbox 和重启恢复；
- Node 与 Runtime 的会话式中立协议；
- Interaction 暂停、精确绑定、重投、ACK、同 Session 恢复；
- 可信终态和敏感数据边界；
- Web 私有只读和 Sites 无持久化边界；
- 两个 E2E 闭环及独立 Gate。

## 保留项

- Java 21、Spring Boot 3.5、Maven；Service 使用 Spring JDBC、SQLite、Liquibase；
- Node 为 Spring Boot 非 Web 应用，Windows 优先；
- Web 使用现有 Sites/Vinext/React/TypeScript 项目；
- Service 的 800 字符短摘要、64 KiB 消息上限、严格字段校验和 scoped bearer credential；
- 完整证据只留在执行电脑；
- Mission revision/digest 与显式 `PASSED` 验收；
- v0.2 当前实现、恢复 tag 和遗留删除 Gate。

## 明确不冻结为实现承诺

- 多 Node 调度策略、多 Agent、多 Runtime 并行；
- 通用工作流引擎或可视化流程编辑器；
- Server 回连 Node、浏览器直连 Node；
- Web 写操作；
- 自动风险审批；
- 完整 xlf 目录规范重构；
- 文件上传、日志下载、远程 shell、D1/R2；
- 生产部署、凭证轮换和遗留删除。

## 合同版本

- Control Loop v2：本冻结的目标语义，见 `contracts/control-loop-v2.md`；
- v2 语义已编码为 Client/Node OpenAPI 和合同 fixtures，并成为当前源码树唯一协议；
- `v0.2.0-mvp1-rc1` 从未形成真实兼容迁移需求，旧协议实现和合同已在用户独立授权下移除；
- v0.2 历史事实继续由不可变 tag 和 release 记录保留，不构成当前兼容承诺。

## 变更控制

冻结后以下变化必须先新增 ADR，并由架构 owner 更新冻结版本号：

- 模块职责或依赖方向；
- 跨模块字段、状态、命令、事件或 ACK 语义；
- 数据落点或敏感数据边界；
- 完成判定、Interaction 绑定或通知投递语义；
- 网络方向、安全模型或 Sites 持久化方式。

模块内部类名、包名、表索引、UI 布局和 Adapter 私有协议可以在不改变冻结合同的前提下独立演进。

## Gate

设计冻结只授权开发任务拆分，不自动授权编码、合并、发布、服务器部署、Sites 发布、公司电脑安装、生产写入或遗留删除。每一项仍需单独 Gate。
