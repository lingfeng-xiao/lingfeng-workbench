---
status: authoritative
authority: main
source_ref: architecture-v1
owner: workbench-node
superseded_by: null
last_verified: 2026-08-19
---

# Runtime 合同

## SPI

所有 agent runtime 通过 Node 内部 SPI 实现以下能力：

- `probe()`：只检查 Runtime 是否可用，不创建业务 Session。
- `capabilities()`：返回 runtime kind 和可协商能力。
- `start(context, sink)`：以不可变 Mission 快照开始执行并输出归一化事件。
- `resume(context, sink)`：使用原 Mission、Runtime handle 和精确交互响应恢复。
- `cancel()`：尽力终止当前执行；重复调用必须安全。

归一化事件固定为 `Started`、`Progress`、`InteractionRequested`、`Finished`、`Failed`
和 `Interrupted`。Runtime 专用命令、Session 字段和原始事件不得越过适配器边界。

## WS 终态规则

WS 的输出只有满足以下全部条件才能产生通过终态：

1. 出现结构化 `lingfeng.terminal` 事件；
2. `missionDigest` 与 Assignment 完全一致；
3. `runtimeOutcome` 为 `SUCCEEDED`；
4. `acceptanceStatus` 为 `PASSED`；
5. `resultSummary` 经压缩后不超过 800 字符。

进程退出码为 0、普通文本完成语、WS step-finish 或 Session 正常关闭均不能替代上述终态。
缺少结构化终态、digest 不匹配、非法状态组合或解析失败均上报 `UNKNOWN`，不得完成 Mission。

WS Session ID 仅写入本地 SQLite；原始 stdout/stderr 和完整文本结果只写入本地 Run 目录。

## Interaction

SPI 在 MVP-N1 中保留 `InteractionRequested` 与 `resume` 形状，以避免 Runtime 绑定；Node Protocol
的精确 Interaction 合同属于 MVP-N2。MVP-N1 收到交互请求时取消当前 Runtime，并以
`INTERRUPTED/UNKNOWN` 安全终止，不猜测回复或自动恢复。
