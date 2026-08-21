---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: architecture-v3
owner: architecture
superseded_by: null
last_verified: 2026-08-21
---

# 全局架构

## 1. 模块与控制面

```text
Browser
  -> Workbench Web / same-origin BFF
  -> Workbench Service                 global control plane
       ^
       | Node outbound HTTPS poll / ACK / event
       v
     Workbench Node                    local control plane
       |
       | loopback HTTP/SSE
       v
     local WS/OpenCode Server          execution plane
       |
       v
     source / tools / local artifacts
```

- Service 是 Task、WorkItem、Mission、Run、Interaction、验收和审计的全局权威。
- Node 是本机上下文映射、Session 绑定、原始证据和本机验收的权威。
- Web 是私有用户界面和同源 BFF，不是第二个状态机。
- WS/OpenCode 执行 prompt 和工具；Node 只薄封装原生 Session、message、event、permission/question、status 和 abort。
- Hermes 可作为外部 Client API/通知适配器，但不直接控制 Node，也不参与 Runtime 完成判断。

三模块不共享源码、DTO、Entity、数据库或构建产物，只通过 `doc/contracts/` 中的 HTTP 合同交互。

## 2. 网络与进程方向

- Service 永不回连 Node；Node 不开放业务入站端口。
- Node 使用出站 HTTPS 拉取 durable command，落盘后 ACK；本地事件先写 outbox，Service ACK 后才删除。
- Node 到 WS 只使用显式配置并通过 health/version/capability Gate 的 loopback endpoint。
- 浏览器不直连 Service 或 Node，只访问 Web 同源 BFF；BFF 在服务端使用分离的读写 credential。
- WS SSE 只存在于 Node 与本机 WS 之间，不进入 Service/Web 网络边界。

## 3. 数据所有权

Service 可保存：业务 ID、Task/Mission 合同、Run/Interaction 状态、短阶段/进度/结果、幂等记录、通知和必要审计。短文本和协议消息继续受合同大小限制。

Node 本地保存：Session/server/workspace 绑定、Mission 快照、commands、原始 SSE、messages、tool events、diff、完整日志、stdout/stderr、验收报告、绝对路径和 outbox。

Service、Web 和 Hermes 禁止接收 Session ID、本机绝对路径、源码、完整对话、原始 Runtime 事件、完整日志、diff 和产物。Sites D1/R2 保持 `null`，浏览器存储不保存业务状态或 credential。

## 4. Run、Session、Runtime 与验收

- 一个 Task 可以产生多个 WorkItem/Mission/Run；重试新增历史，不覆盖旧 Run。
- 一个 Run 恰好绑定一个本机 OpenCode Session；无法证明原 Session 身份时进入 `uncertain`，不得新建替代 Session。
- 一个 Mission 通常只有一次初始 prompt；真实追问或 Interaction 才增加 message，不存在固定 Turn 协议。
- Session idle 只说明 Runtime 已收敛；业务完成还必须由 Node 本机 AcceptanceEvaluator 用可核验证据产生 `PASSED`。
- `Run=completed` 只触发 Task `REVIEW/PENDING`；用户明确接受后 Task 才是 `DONE/ACCEPTED`。

## 5. 安全与并发边界

- 首轮每个 Node 只允许一个活动 Run，避免工作区副作用竞争。
- `executionProfile` 只是 Node 本机受信命令配置别名；Service 不能下发任意 shell。
- Interaction response 必须精确绑定 run、mission digest、interaction 和 checkpoint/request ID，并保证重复投递不产生第二次副作用。
- cancel 调用原生 Session abort；失败或状态不明确时 fail closed。
- 不提供远程 shell、文件下载、浏览器直连 Node、自动风险审批或跨 Node Session 迁移。

产品状态见 [design-freeze-v0.5-business-loop.md](design-freeze-v0.5-business-loop.md)，流程见 [workflow.md](workflow.md)，Runtime 决策见 [decisions/ADR-003-native-opencode-runtime.md](decisions/ADR-003-native-opencode-runtime.md)。
