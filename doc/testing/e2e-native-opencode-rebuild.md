---
status: current-evidence
authority: working-tree-evidence
source_ref: ADR-003-local-e2e
owner: integration
superseded_by: null
last_verified: 2026-08-20
---

# ADR-003 原生 OpenCode 重建验证

## 范围

本次只验证未提交工作树中的新 Node 内部边界；没有调用真实 WS prompt、没有修改生产系统、没有部署或安装。

验证形态：

- OpenJDK `21.0.12`；
- 根 Maven reactor：真实 Service 测试 + Node 测试与可执行 JAR；
- Runtime 边界单测：fake OpenCode HTTP/client，覆盖原生 endpoint、一次 prompt、status/SSE、permission 和 abort；
- 组合 E2E：真实 Service/Node JAR、deterministic fake Session/AcceptanceEvaluator、真实 Web production Worker；
- 临时 HTTPS、SQLite、workspace 和 evidence；不连接生产数据。

## 最终组合证据

```text
evidenceRoot D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-control-loop-e2e-pB2Ar6

FLOW
workItemId  wi_ebfbd359f6a34973a649f3c04a849a42
runId      run_5b1873b189d340fa8aa1b1f6009b7aa9
prompts    1
status     completed

NOTIFY
workItemId       wi_1f86bbc532fe4cebaab8c28855d4b28d
runId            run_3e9850507ffa4398a5e160409f04ce9d
interactionId    int_001
responseCommand  cmd_ac95ad6f2c604de4a1de41ffb0c34f2d
status           completed / consumed
```

FLOW 在 Node 进入 running 后停止 Service。fake Runtime 完成一次 Mission、独立 fake evaluator 通过，本地 terminal/outbox 在 Service 离线时保留；Service 使用同一数据库重启后 outbox 收敛到 completed，Web 渲染已完成。Node 只有一个 Session 绑定，evidence 文件齐全。

NOTIFY 验证 Interaction pending → delivery → resolve → Node durable ACK → 原 Session 原生响应消费；重复 delivery、resolution 和 ACK 均幂等，Session 记录数仍为 1，Web 渲染已消费。

## Gate 结论

- 当前根 reactor：Service 7 项、Node 31 项全通过；OpenCode HTTP/SSE 3 项、WS Adapter 4 项通过。
- `node --check integration/control-loop-e2e.mjs` 通过；`git diff --check` 通过。
- Service SQLite/WAL 未出现 fake Session handle、workspace 绝对路径或 Node runtime evidence 名称。
- 组合 fake 证据本身不证明真实 Midea WS；后续真实探针已补充工具、question reply 和已完成 Session reattach。真实 permission、活动 Run 重启恢复、SSE 故障注入和真实业务验收仍按 research 文档列出的 Gate 实测。
