---
status: proposed
authority: DF-0.4-real-adoption
source_ref: plan-v0.4-real-adoption
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# v0.4 真实业务渐进验证计划

## 1. 目标

在不扩大现有数据、网络和发布授权的前提下，让 v0.3 控制环从 deterministic fake 边界逐步进入真实 WS 日常使用。每一阶段只增加一种风险，必须有客观证据、停止条件和可执行回退；通过当前阶段不自动授权下一阶段。

首要目标不是提高任务量，而是证明以下最小真实闭环稳定成立：

1. 真实 WS 能创建一个 Agent Session；
2. 同一 Run 在同一 Session 内完成三个 Turn；
3. Node 保存完整本地 evidence，Service/Web 只保存和展示最小投影；
4. digest、checkpoint、acceptance 和可信 terminal 一致；
5. Service、Node 或网络短暂中断后不重复 Runtime 副作用；
6. 失败和不确定状态不会被解释为完成。

## 2. 当前起点

- v2-only Service、Node、Web、合同和 fake E2E 已通过；
- WS `0.0.0--202608171122` 的直接 `run` smoke 已返回结构化事件和真实 Session；Node/Service 真实控制环 Run `run_339aaccefe294a59bb6983e254843634` 已在唯一 Session `ses_fe27959baffe0jHllmrR2WwEc0` 完成 3/3 Turn 和可信 `SUCCEEDED/PASSED` terminal；
- 最终适配后另有成功 Run `run_66ad1083a274422d8fa3cacd8849368b` 与 `run_14d0fc03221a4086b5fbf9ca098f2887`。R1 当前为连续成功 Run 3 个、固定样本类型 1/3；
- `ws providers list` 的 0 credential 与 `ws models` 的空输出不是本机默认 agent/model 可用性的可靠判据，真实 `run` smoke 才是执行 Gate；
- 真实 Interaction、checkpoint/resume、Service/Node 故障注入、服务器 v0.3 部署、Node v0.3 安装和 Sites 新版本均未验证或未授权；
- 旧 Python、旧 D1 和当前安装版 v0.2 Node 继续受独立清理 Gate 保护。

本轮曾保留三类真实失败证据：WS 瞬时 `no providers found`、合成 Mission 文案被拒绝、自然状态 `completed/passed` 被协议 fail closed。stdin EOF、terminal 顺序、自然任务提示和允许枚举均已针对性修复；不能删除这些失败证据或只记录成功样本。

## 3. 固定护栏

- 单 Node、单并发 Run；同一时刻只允许一个真实 WS 子进程；
- 初始任务只使用临时隔离 workspace，禁止生产仓库、生产系统写入和外部消息；
- 前两阶段禁止文件工具、shell、浏览器、网络写入和第三方 API；
- 每个 Mission 有明确输入、三 Turn 上限、10 分钟总超时和人工停止点；
- WS credential 只存在于本机官方凭证存储，Service、Git、日志和证据目录不得出现密钥；
- Hermes 继续使用本地受控边界，真实微信必须单独授权测试账号和收件人；
- Service 永不回连 Node；Node 保持只出站 HTTPS；完整会话和原始证据只留 Node；
- 任一 UNKNOWN、Session 漂移、错误 digest/checkpoint、无 terminal 或证据缺失立即停止扩量。

## 4. 分阶段 Gate

### R0：Runtime 就绪（已通过）

验收：

- `ws --version` 成功并记录版本，不记录 secret；
- 直接的 60 秒无工具 WS smoke 输出结构化事件、真实 Session ID 并正常退出；
- Node `RuntimeProbe` 在 15 秒内成功，错误时给出可诊断原因；
- `providers/models` 输出只作诊断信息，不覆盖真实 smoke 的执行证据；
- 无凭证、Session 原始内容或本地路径进入 Service/Web 投影。

停止条件：直接 smoke 登录失败、默认 agent/model 不可用、需要生产 credential、输出包含 secret 或 smoke 超时。

### R1：本机真实无工具三 Turn

形态：真实 Service JAR + 真实 Node JAR + 真实 WS；Hermes 仍为 fake；Web 使用 production build。

使用三个固定、无生产副作用的任务样本：

1. 将 Mission 内给定的短文本整理为三条验收项；
2. 对给定的三个数字做可人工复算的汇总；
3. 根据给定约束生成不超过五项的执行清单。

每个样本必须在同一 Session 内完成三个 Turn。至少连续成功 3 个 Run，且一次失败也必须可解释、可重跑而不覆盖原证据。

当前进度：数字汇总样本已在最终适配后连续成功 3 次；短文本验收项与约束清单尚未运行，因此 R1 未完成。

验收：

- 每个 Run 有真实且稳定的 Session ID，Session 记录数为 1；
- `submittedTurns=3`、`finishedTurns=3`，terminal 为合同允许的可信完成；
- Mission digest、acceptance 与最终投影一致；
- Node evidence 包含 mission、command、conversation、runtime events、stderr、checkpoint 和 result；
- Service SQLite 和 Web 输出不含 Session、workspace 路径、原始对话或密钥；
- Service/Web 重启后仍显示相同 completed 投影。

### R2：恢复与幂等 canary

只对 R1 已通过的固定样本注入一种故障，每次 Run 只注入一个变量：

1. Turn 2 期间停止并重启 Service，验证 Node 本地继续和 outbox 重放；
2. command ACK 前后各重启一次 Node，验证不重复创建 Session；
3. 重放相同 command/event/ACK，验证响应与状态幂等；
4. 模拟短时网络不可达，验证 backoff、恢复顺序和最终一致性；
5. 在 terminal 与 cancel 竞争中验证可信 terminal 和状态机优先级。

Node 重启后的真实 Session 恢复只有在 WS 明确提供 durable resume capability 时才测试；否则必须保持 fail closed，并把该场景标为不支持，而不是降级成新 Session。

### R3：受控 Interaction

前置：WS 明确支持可控 Interaction 和同 Session resume；若能力探测不支持，本阶段不执行。

步骤：真实 WS 请求 Interaction → Service 创建 Interaction/Notification → 本地受控 Hermes 获取通知 → 人工输入固定答案 → resolve → Node ACK → 原 Session 继续完成。

验收：

- waiting 时保留真实 Session ID 和 checkpoint；
- 错 digest、checkpoint、Node 和状态全部拒绝；
- 重复 delivery、resolve、response command 和 ACK 不产生第二次副作用；
- Interaction 最终为 consumed，Run completed，Session 数仍为 1；
- 不发送真实微信。

### R4：低频日常 canary

仅在 R1-R3 连续通过后启用。初始一周限制为：

- 每天最多 1 个 Run；
- 仅白天人工在场时启动；
- 只允许无工具或隔离 workspace 内的只读任务；
- 每个 Run 启动前人工确认 Mission、workspace、model 和最大时长；
- 每个 Run 完成后人工核对 result、Node evidence、Service/Web 投影和敏感数据扫描；
- 任一不确定状态立即停止后续 canary，回到 fake E2E 和单元测试定位。

达到连续 7 个成功 canary、0 次数据越界、0 次误完成后，才提出“隔离 workspace 文件写入”设计评审。文件写入、真实 Hermes/微信、服务器部署、Node 安装和 Sites 发布仍分别需要新授权。

## 5. 可观测性与日记账

每个真实 Run 必须记录：

```text
executedAt
WS version / provider / model（无 secret）
workItemId / missionId / runId / missionDigest
runtimeSessionId（只在 Node 本地证据）
每个 Turn 的 submitted/started/finished 时间
checkpoint 与 terminal 分类
Service/Node 重启和网络故障时间线
Node evidence 目录与文件大小
Service/Web 最终投影
敏感数据扫描结果
人工验收人和结论
```

仓库中的权威文档只记录最小标识、结果和哈希；完整 Session、原始事件、路径、日志和产物继续只留 Node。

## 6. 停止与回退

以下任一条件立即停止真实 canary：

- Session ID 缺失或变化；
- WS 无结构化终态、提前终态或异常退出；
- Run 卡在 running/waiting 超过 Mission 超时；
- Node evidence 不完整或 Service/Web 泄漏本地数据；
- 重启后重复创建 Session、重复执行 Turn 或重复外部副作用；
- provider/model/WS 版本未经确认发生变化；
- 安装版 Node、服务器 Service、Hermes 或 Sites 出现非本轮预期变化。

回退动作限于停止本轮临时进程、禁用本轮 credential/config、保留 SQLite/WAL/日志并回到最后一个 fake E2E 通过的构建。不得 reset、清理证据、删除旧资产或用人工 SQL 修改状态。

## 7. 下一轮：R1 收口

下一轮不扩大副作用边界，只完成 R1：

1. 用“给定短文本整理三条验收项”完成一个真实 3 Turn Run；
2. 用“根据给定约束生成不超过五项的执行清单”完成一个真实 3 Turn Run；
3. 每个 Run 都核对唯一 Session、`submittedTurns=3`、`finishedTurns=3`、digest、`SUCCEEDED/PASSED`、本地 evidence 完整性和 stderr；
4. 对两个 Run 均读取 Service 最小投影并使用 Web production Worker 渲染；至少对最后一个 Run 使用同一 SQLite 重启 Service 后再次查询和渲染，确认 completed 投影持久化；
5. 扫描 Service SQLite/WAL 与 Web 输出，拒绝 Session ID、workspace 路径、原始对话、runtime events 或凭证泄漏；
6. 保留所有成功和失败目录，把两个新样本接入当前连续成功序列。如果任一 Run 进入 UNKNOWN、Session 漂移、terminal 不可信或 evidence 不完整，立即停止 R1 扩量并回到单样本定位。

R1 全部通过后只提交 R2 的单变量故障注入执行单，不自动开始 R2。建议的首个 R2 canary 是 Turn 2 期间仅中断 Service，验证真实 WS Session 在 Node 内继续、outbox 重放后 Service/Web 收敛；Node 重启、Interaction、真实 Hermes/微信、文件工具、部署和安装继续分别等待新的 Gate。
