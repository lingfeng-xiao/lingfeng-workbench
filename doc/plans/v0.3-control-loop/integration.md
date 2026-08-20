---
status: authoritative
authority: DF-0.3-control-loop
source_ref: plan-v0.3-integration
owner: integration
superseded_by: null
last_verified: 2026-08-20
---

# 集成、E2E 与发布任务

## I-001：合同一致性 Gate

- 对 Service、Node、Web 分别运行 v2 fixtures；
- 验证 v2 是唯一协议、旧路径不存在且未知字段拒绝；
- 检查模块没有共享 DTO/源码依赖；
- 任何合同修正只由 architecture owner 修改，模块暂停猜测实现。

## I-002：E2E-FLOW fake Runtime

形态：真实 Service JAR + 真实 Node JAR + fake session Runtime + Web production build。

步骤：创建 WorkItem/Mission → Node 存储 START_RUN → 打开 Session → 三个 Turn → 中途隔离 Service 网络 → 本地继续并积累 outbox → 恢复网络 → 重放阶段/进度 → 结构化 PASSED terminal → Web 看到 completed。

验收：

- 心跳与 Runtime 执行互不阻塞；
- 相同 assignment 只打开一个 Session；
- Service 重启后完成状态保持；
- Node 有完整本地证据；
- Service/Web 无 Session、路径、原始事件、diff 和完整结果。

## I-003：E2E-NOTIFY fake Hermes

步骤：fake Runtime 请求 Interaction → Node 保留 Session → Service 同事务创建 Interaction/notification → fake Hermes poll 并 DELIVERED → 使用精确绑定 resolve → Service 重投 response command → Node 落盘 ACK → 同 Session 消费 → Agent 继续并完成。

验收：

- 重复 notification poll/report 幂等；
- 重复微信等价回复和 resolve 不产生第二响应；
- Node 未 ACK 前 Service 持续重投；
- delivered 与 consumed 清楚分开；
- wrong digest/checkpoint/node/state 全部 fail closed。

## I-004：故障矩阵

至少覆盖：

```text
Service 在 command 创建后崩溃
Node 在 command 落盘前/后崩溃
Node 在 response ACK 前/后崩溃
Runtime 在 waiting interaction 时丢失
Hermes 在微信发送前/后崩溃
cancel 与 PASSED terminal 竞争
Node 离线告警重复触发
Sites 上游 timeout/401/403
```

每个场景必须证明不会重复 Runtime 副作用、不会误 completed、不会泄漏本地数据。

## I-005：真实 Runtime Gate

前置：用户完成官方 Runtime 登录、模型可用、受超时限制的无工具 smoke 正常。先验证真实 Runtime 的 Session、Turn、Interaction/capability；不支持的能力必须显式降级或阻断，不能由 Adapter 伪造。

首个真实任务仍为无文件工具 Mission。文件任务必须另做 workspace/副作用授权和越界测试。

## I-006：Hermes/微信 Gate

Workbench 仓库只提供 Client API 合同和 fake Hermes E2E。真实 Hermes 改动在其仓库独立评审，必须实现 notificationId 幂等、delivery report、Interaction 精确 resolve 和微信身份映射。真实微信发送属于外部写入，需单独授权和测试账号/收件人确认。

## I-007：生产形态 smoke

分别授权后执行：

1. 备份服务器 SQLite，部署 Service v0.3，并提供稳定 HTTPS 域名；
2. 在公司电脑安装签名/校验后的 Node JAR，配置企业代理和独立 credential；
3. 私有发布精确 Web commit，D1/R2 保持 null；
4. 部署/配置 Hermes v2 adapter；
5. 运行一次无工具 FLOW 和一次人工 Interaction；
6. Web 验证状态，服务器和浏览器做敏感数据扫描。

服务器部署、Node 安装、Sites 发布、Hermes 外部写入必须是四个独立 Gate。

## I-008：冻结版本与遗留清理

只有 I-005、I-006、I-007 全部通过，才可提出 release/tag Gate。冻结 release 记录精确 commit、两个 JAR/Web commit、SHA-256、数据库 migration、合同版本和 E2E 证据。

旧 Python、旧任务、旧 D1 和其它资产仍按 `asset-inventory.md` 的破坏性 Gate 处理；发布成功不自动删除。删除前再次确认恢复 tag、精确 SHA 和恢复步骤。
