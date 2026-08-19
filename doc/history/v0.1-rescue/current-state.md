---
status: current-evidence
authority: historical-main
source_ref: main@7d5fcc2d1208e98532b33e5e91c1a04195f3a438
owner: history
superseded_by: doc/current-state.md
last_verified: 2026-08-19
---

# 当前状态冻结

快照时间：2026-08-19（Asia/Shanghai）  
事实基线：main@968b88d9f869b0ed7a42c91e67c911f2c1e5b36c  
完整审计入口：[Issue #15 — MVP Rescue](https://github.com/lingfeng-xiao/lingfeng-workbench/issues/15)

本文是让新电脑快速恢复上下文的当前投影。Issue 和 PR 保留完整事件历史；如事实变化，应通过新的已接受变更更新本文，而不是只在评论中覆盖。

## 一句话状态

- MVP-A：已跑通。
- MVP-B：被“恢复时丢失原 Mission 合同”和“裸快捷审批可能错配”阻塞。
- 完整 v0.2、Sites、D1、R2、效率版本和其它产品化工作：冻结。
- 当前没有后台开发任务运行。
- 本冻结未授权任何合并、部署、权限扩大、生产写入或真实审批重放。

## MVP-A：已跑通

真实验收样本：

| 项目 | 值 |
|---|---|
| WorkItem | wi_38eee7a22d655c3d85affb0aeaaab8f1 |
| Mission | mi_7a5ff7fd84f15093874f9ca2d746a8af |
| Run | run_5fc3b3991b554f0c8cd67016bb13ac8b |
| Node | office-pc |
| WS Session | ses_fe7d9d42effeW3NfvfS7snCBW4 |
| 终态 | completed |
| Hermes 短结果 | WS-NODE-HERMES / LINGFENG_MVP_A_OK |

已经由真实链路证明：

1. Hermes 的确定性命令创建任务。
2. 只有指定的 office-pc Node 领取任务。
3. WS 建立真实 Session 并执行，不是 mock。
4. Hermes 至少收到一次进度。
5. Hermes 收到明确终态。
6. Hermes 保存短结果；办公电脑本地保存 mission.json、runtime-events.ndjson、runtime-stderr.log 和 result.md。

限制：另一次只读文件任务发现 WS glob 没有把白名单工作区作为硬边界。没有读取文件内容、写文件或联网，该 Run 已失败并停止。因此，目前只认可“无工具合成任务”的闭环；任何本地文件工具必须等工作区硬隔离修复后才能启用。

## MVP-B：被具体问题阻塞

第一次尝试使用 exact Interaction ID 批准：

- Run：run_616fb373ae234a89a942bbaa300cd75e
- Interaction：ix_5017402d2a724a308d549071125aede8
- WS Session：ses_fe7d6f448ffe9hhX4I2C3T1Ku4

批准确实回到同一 Run 和同一 Session，但恢复输入没有带回原 Mission 的目标、验收条件和授权边界。WS 因而无法继续原任务；服务端还错误地把“不满足验收但正常退出”记录为 completed。

第二次尝试原计划做 exact reject：

- Run：run_2874eb3d74e24065941a420ea1e4c38c
- Interaction：ix_db8bb8978bd14675922b48e7709cae8e

计划中的拒绝尚未执行时，一条延迟或错位的微信输入先批准了新的 Interaction。恢复进程被立即停止，Run 为 failed，本地没有完成工具动作。由此确认：裸 /lf y 即使当前只有一个待审批项，也可能在时间上产生歧义。

MVP-B 重新验收前至少需要：

1. 恢复消息携带并校验原 Mission 合同。
2. 业务终态按 Mission 验收标记判断，不能只看 Runtime 正常退出。
3. 救援模式只接受 exact Interaction ID；不消费无 ID 的 y/n。
4. Hermes 与 Node 同时校验 interaction_id、run_id 和 checkpoint_id。

最小修复提案记录在 [Issue #16](https://github.com/lingfeng-xiao/lingfeng-workbench/issues/16)，尚未获得实施授权。

## 已冻结的产品化产物

以下 head 只用于恢复历史上下文，不授权合并：

| 范围 | 位置 | exact head |
|---|---|---|
| W0-A | [PR #7](https://github.com/lingfeng-xiao/lingfeng-workbench/pull/7) | ac20e056b5a569f8350016f4850b8249f8d69b52 |
| W0-C | [PR #8](https://github.com/lingfeng-xiao/lingfeng-workbench/pull/8) | a451306184a64b899f562f6731667ad232a89d75 |
| W0-B | [PR #9](https://github.com/lingfeng-xiao/lingfeng-workbench/pull/9) | a2d18163481128fda5e4ef2bc84c184306152813 |
| 完整 v0.2 设计 | [PR #1](https://github.com/lingfeng-xiao/lingfeng-workbench/pull/1) | 3b8d8c0e5cdb5c44847c2a660bd6345b9e04d3ec |

Issue #10、#12、#13、#14 及上述 PR 均保持冻结。PR #1 的五份详细文档是历史产品化输入，不是当前执行权威。

## 当前实现与目标设计的区别

当前 v0.1 救援实现把 Hermes Plugin、最小控制数据、SSH worker-stream、Node、WS 适配和一个 Kanban 适配放在同一代码基线中。MVP-A 证明的是这条现有链路能产生用户价值，不代表这种耦合就是长期设计。

Kanban 适配仍存在于恢复源码中，但 Kanban 已从目标设计移除，不再作为过渡方案或长期需求池。未来是否拆分组件、采用何种数据库、如何接入 Sites，必须另行设计和确认。

## 新电脑恢复顺序

1. 读取本仓库 main 的 README.md、docs/current-state.md 和 docs/design.md。
2. 确认 main exact SHA；不要把 Draft PR head 当成已接受基线。
3. 如需完整审计，再读取 Issue #15 和上述冻结 PR。
4. 在获得对应 Gate 前，不合并、不部署、不扩权，也不在公司电脑开发或推送 workbench 源码。
