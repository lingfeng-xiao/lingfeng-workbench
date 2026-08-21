---
status: authoritative
authority: DF-0.5-trusted-loop
source_ref: node-current
owner: workbench-node
superseded_by: null
last_verified: 2026-08-21
---

# workbench-node

`workbench-node` 是执行电脑上的本机控制面。它主动连接 Service，直接调用本机 WS/OpenCode Server，并把完整执行和验收证据留在本机。

## 职责

- durable poll/ACK/outbox、单活动 Run 和重启恢复；
- 本机 `workspaceRef/contextRef` 解析和允许根校验；
- loopback endpoint、health、version、agent/provider/model capability Gate；
- 一个 Run 绑定一个原生 Session，一次初始 Mission prompt，SSE 与 reconciliation；
- 原生 permission/question、reply/reject、abort 和同 Session reattach；
- Node-only evidence 与受信 `executionProfile` 验收；
- 向 Service 只投影有界 phase/progress/Interaction/terminal。

## 非职责

- 不保存或决定全局 Task 业务状态；
- 不实现 CLI/三 Turn/terminal JSON、端口扫描、编辑器 terminal proxy 或第二套审批协议；
- 不向 Service 上传 Session、绝对路径、原始事件、conversation、diff、完整日志或产物；
- 不接受 Service 下发任意命令，不把 workspace 映射宣称为 OS 沙箱。

内部边界和失败规则见 [detailed-design.md](detailed-design.md)。
