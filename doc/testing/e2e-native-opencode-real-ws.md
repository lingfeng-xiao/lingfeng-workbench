---
status: current-evidence
authority: release-candidate-evidence
source_ref: v0.5.0-trusted-loop-rc1
owner: integration
superseded_by: null
last_verified: 2026-08-21
---

# ADR-003 真实本机 WS 探针

## 范围

本次使用冻结候选工作树中实际的 `HttpOpenCodeClient`、`WsEndpointResolver` 和
`WsSessionRuntimeAdapter`，直接连接本机 Midea WS/OpenCode server。探针只使用全新临时目录，
没有修改仓库文件、生产数据或外部系统，也没有提交、推送、部署或安装。

- JDK：Temurin `21.0.12`；
- endpoint：最初为 `http://127.0.0.1:54014/`；IDE 重启 server 后由进程所有权只读核验为 `http://127.0.0.1:58433/`；
- health/version Gate：`healthy=true`，`0.0.0--202608171122`；
- 旧 `127.0.0.1:4096` 实例版本为 `0.0.0--202608031304`；最终可信业务闭环在动态实例退出后经精确 capability Gate 使用该仍健康实例；
- 每个探针只创建一个 Session、只提交一次 `prompt_async`；
- 最新 Node 显式绑定 `agent=build`、`provider=workspace`、`model=gpt-5.6-luna`，启动 Gate 会验证三者存在；
- Node 通过原生 SSE 接收事件，通过 status/messages reconciliation 判断状态；
- 业务产物由探针直接读取和解析，不以 assistant 文本作为验收结论。

## 结果

| 探针 | Session | Runtime 结果 | 独立产物验收 | 结论 |
|---|---|---|---|---|
| 无工具 `READY` | `ses_fddeba537ffe3o3oTXBOTCUHKz` | 收到 `busy`、assistant `READY`、`session.idle` 和 Node `RuntimeIdle` | assistant 最终文本为 `READY` | 原生 Session、一次 prompt、SSE、messages、idle 和本地证据链完整通过 |
| 17 + 25 | `ses_fddf1565fffeNdLbnH31Xxj9sW` | read/write tool 均 completed，但 5 分钟内一直 `busy`，没有 `session.idle` | `result.json` 精确为 `{"sum":42}`，输入未变 | 工具执行和业务产物通过，Runtime 收尾失败；原生 abort 返回 `true` 并清除活动状态 |
| `hello` 转大写 | `ses_fddeab8bfffe9exkxTnM5iazw1` | read/write tool 均 completed，但 2 分钟内一直 `busy` | `output.txt` 字节为 `HELLO\n`，输入未变 | 第二次复现工具后不收尾；Node `cancel` 调用原生 abort，发出 `ABORTED` 并清理 Session |
| 原仓库只读 `pom.xml` | `ses_fdde1a5bdffeJTkp65pCpQo4a7` | 2 分钟内保持 `busy`，尚未产生 tool part | 仓库 `git status` 没有新增探针修改 | 排除“只有临时目录会卡住”；本次还暴露 abort 回调竞态 |
| 显式 `gpt-5.6-luna` 读写 | `ses_fddc6f41cffeCgTvJDOZKdzxG3` | read/write completed，约 16 秒进入 `idle` | `result.json` 为 `{"sum":42}` | 相同工具任务正常收尾 |
| 显式 `hw-glm-5` 读写 | `ses_fddc5f988ffeTiTQTxgkrlLk91` | read/write completed 并进入 `idle` | `result.json` 为 `{"sum":42}` | 不能把早期卡住稳定归因于单一模型 |
| 隐式 agent/model 读写 | `ses_fddc4e991ffeoM7FXFCGQbDgMr` | completed 并进入 `idle`，实际落到 `hw-glm-5` | `result.json` 为 `{"sum":42}` | 请求合法，但隐式 IDE 状态不适合作为 Node 配置 |
| 真实 Node client/SSE 对照 | `ses_fddc3c6b2ffe4O66PpBwRLi7jp` | `busy -> tools -> DONE -> idle` | `result.json` 为 `{"sum":42}` | `HttpOpenCodeClient + Adapter + SSE` 路径通过 |
| 最新显式 target canary | `ses_fddb09a91ffedomiwlf9fDZZRy` | Gate、Session、一次 prompt、SSE、tools、messages、`RuntimeIdle` 全通过 | `result.json` 精确为 `{"sum":42}` | 当前编译代码的核心 Node→WS 路径通过 |
| 原生 question 与同 Session 继续 | `ses_fdda36619ffetYCw5BMZWxFgZV` | Node 收到 question、回复 `BLUE`，Session 继续执行并进入 `idle` | `answer.txt` 精确为 `BLUE` | Interaction request/reply 真实通过 |
| 已完成 Session reattach | `ses_fdda36619ffetYCw5BMZWxFgZV` | 新 Adapter 只读绑定原 Session，status/messages 对账为 `RuntimeIdle` | 没有创建第二个 Session | completed Session 恢复身份与对账通过 |
| Task 可信业务闭环 | `ses_fdd50c1bfffecr1wZUiihzisso` | WS 修改临时 Maven canary 后原生 idle；Node 独立执行固定 profile | `mvn -DforkCount=0 test` exit 0，Surefire 报告存在 | Run completed，Task `REVIEW/PENDING -> DONE/ACCEPTED`，Service 重启、Web render 和敏感边界扫描通过 |

临时证据目录：

- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-real-ws-ready-5010604040042727034`；
- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-real-ws-native-13667334956664447753`；
- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-real-ws-tool-repeat-11410631149841751047`；
- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-real-ws-owned-read-5060334214924722617`。
- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-node-sse-retry-12953083108013227490`；
- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-native-canary-7db381a0f18244669c836604a68a8c3f`。
- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-control-loop-e2e-QTDYy1`（最终完整业务闭环）；
- `D:\Users\ex_xiaolf7\AppData\Local\Temp\lingfeng-trusted-ws-canary-20260821-1231`（隔离 Maven workspace）。

`READY` 探针保存了 `runtime-events.ndjson` 和 `conversation.ndjson`，共观察到 23 个原始事件，
其中包含一个 `session.idle`。第一次工具探针保存了 157 个原始事件，包含
`tool.preExecution`、`tool.executed` 和 `file.edited`，但不包含 `session.idle`。第二次工具探针
在超时取消前同样没有业务完成；取消过程中 WS 发出 error/idle 原始事件，Adapter 因 Session 已标记
aborted 而没有将其误投影为 `RuntimeIdle`。

随后对 agent/model 和调用端做单变量对照。显式 `gpt-5.6-luna`、显式 `hw-glm-5`、隐式目标和真实
Node client/SSE 四组工具任务都正常进入 `idle`。因此早期工具后长期 `busy` 是当前上游链路的间歇性
故障样本，现有证据不能稳定归咎于某个模型，也不能据此在 Node 内猜测完成。

最新工作树进一步让 `prompt_async` 显式携带 `build / workspace / gpt-5.6-luna`，并在 endpoint Gate
只读核验 agent/provider/model。2026-08-21 使用重新编译的实际 Adapter 完成新 canary：创建唯一 Session
`ses_fddb09a91ffedomiwlf9fDZZRy`，观察 `BUSY`、工具读写、assistant `DONE`、`session.idle` 和
`RuntimeIdle`，随后由探针独立解析产物并确认 `sum=42`。另以不存在的 `missing-model` 运行真实 Gate，
得到 `WS configured agent/provider/model is unavailable`，拒绝发生在创建 Session 和发送 prompt 之前。

在 IDE 重启 server 后，旧 54014 endpoint 按预期拒绝连接。没有扫描端口或启动 WS；只读关联
`ws.exe serve --hostname=127.0.0.1 --port=0` 的进程所有权与监听 socket，确认同版本新 endpoint 为
58433。更新显式 endpoint 后，Session `ses_fdda36619ffetYCw5BMZWxFgZV` 真实调用原生 question tool；Node
投影 `que_0225cb5500011AUtcN0Gy5hbVw`、`PROVIDE_INPUT/REJECT`，回复 `BLUE` 后同一 Session 写入
`answer.txt` 并回到 `idle`。随后新建 Adapter，以保存的 server/version/workspace/Session handle 只读
reattach，得到原 Session 的 `RuntimeIdle[resultSummary=DONE]`，没有创建替代 Session。

只读仓库探针在 abort HTTP 调用返回之前收到 `session.error/session.idle`。原 Adapter 是在 HTTP 返回后才
设置 aborted 标记，因此曾短暂投影出错误的 `RuntimeIdle`。工作树已把取消意图提前设置，并在取消中
抑制上游 error/idle 的完成投影；新增同步复现该事件顺序的回归测试。Node 全量测试现为 27 项通过。

## Gate 结论

以下能力已经由真实本机 WS 证明：

- Node 能精确连接正确版本的动态本机 endpoint；
- Node 能创建并绑定唯一 OpenCode Session；
- 一次 `prompt_async`、SSE 实时事件、status/messages reconciliation 可工作；
- WS 能读取和写入授权临时工作区，工具结果能形成可独立验证的业务产物；
- 无工具会话能正常进入 `idle`；
- 原生 abort 和 Node cancel 能停止卡住的 Session。
- 受信本机 Acceptance Profile 能独立于 assistant 文本运行固定命令并写 Node-only 报告；
- Service Run completed、Task REVIEW/PENDING、人工接受 DONE/ACCEPTED、Service 重启和 Web production render 已形成一个真实完整纵切。

当前可以宣称 Node→真实 WS 的核心通信、工具执行和一个 Task 可信业务闭环均已由最新代码通过。它仍不是
稳定性认证：尚未达到连续三个完整真实开发 Run，也尚未完成真实 permission、活动 Run 跨 Node/WS 重启
和受控 SSE 断流恢复成功样本。真实 question reply 和已完成 Session reattach 已通过。

完整闭环前的失败样本同样保留且没有改库补成功。动态 WS `0.0.0--202608171122` 的 Session
`ses_fdd64c123ffennalrHaMK3IGTZ` 修改文件后请求 `bash.proxy.execute.requested`，但没有实际 Maven 子进程；
随后该 WS 进程退出。Node 曾因失败回调扇出在约一分钟产生 3000 多条断线进度，现已修为同一 Session
单重连计划、1/2/4/8/16/30 秒退避和告警去重。另一个 Run 中 `/session/status` 在 assistant 仍 busy 时
短暂返回空 map，旧逻辑提前验收并得到 FAILED；现缺项只能在 messages 证明最终 assistant 已 completed 且
`finish != tool-calls` 时收敛。两处均有单测，Node 当前 39 项通过。

Workspace 本机日志提供了进一步线索但还不足以定责：IDE 自己的 `OpenCodeMainService` 对这些原生 SSE
envelope 持续报 `Cannot read properties of undefined (reading 'sessionID')`；临时目录 tool event 还被某些
窗口记录为“Session does not belong to current project”或“Workspace service is not connected”。Node 能正确
解析相同 envelope，且原仓库只读 Session 也会卡住，所以这些日志是 vendor 集成调查入口，不是可据此
在 Node 中增加私有协议或猜测完成的理由。

Node 必须继续 fail closed：即使产物碰巧满足验收，只要 Runtime 未正常收敛到 `idle`，Run 就不能自动
进入 completed；反过来，`idle` 也不能代表业务验收。默认 acceptance profiles 为空，未登记 profile 仍为
UNKNOWN。最终 E2E 只通过 Node 本机登记的 `trusted-local-e2e-v1`，固定命令不由 Service/Mission 拼接，
完整报告、stdout/stderr 和路径只留 Node。下一稳定性 Gate 是连续真实 Run、permission、活动 Run 恢复和
受控 SSE 故障恢复，不是恢复 terminal JSON、摘要扫描或编辑器 bash proxy。
