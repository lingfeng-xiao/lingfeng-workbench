---
status: authoritative
authority: DF-0.3-control-loop
source_ref: architecture-v2
owner: architecture
superseded_by: null
last_verified: 2026-08-20
---

# 全局架构

## 1. 目标

Workbench 把“业务控制”和“本机执行”分开：Service 保存最小、可信、可查询的控制状态；Node 在执行电脑上管理 Agent 会话；Agent Runtime 才读取源码、调用工具并完成实际工作。系统首先闭合两条链路：

1. **工作流闭环**：任务可在工作电脑无人值守运行，使用一个原生 Agent Session、审批/问答、恢复和独立可信验收；
2. **通知闭环**：Service 产生通知意图，Hermes 通过微信投递并回传审批，Web 能看到相同的短状态。

## 2. 模块与外部组件

仓库只有三个业务模块：

- `workbench-service`：全局控制面和唯一业务状态来源；
- `workbench-node`：工作电脑上的本机控制面；
- `workbench-web`：部署到 OpenAI Sites 的私有只读观察面。

以下是外部组件，不进入仓库业务逻辑：

- **Hermes**：消息投递和 Client API 协议适配器，不判断任务是否完成；
- **Agent Runtime**：实际执行者，WS 只是第一个 Adapter 目标；
- **微信**：Hermes 管理的通知和人工输入渠道；
- **SPM/xlf**：工作电脑上的项目流程资料和完整工作证据，不是 Service 数据库。

## 3. 控制关系

```text
Hermes / Sites Web / trusted client
                  |
             Client API
                  |
        workbench-service        global control plane
                  ^
                  | outbound HTTPS: poll / event / ACK
                  v
          workbench-node         local control plane
                  |
        thin native OpenCode client
                  |
        Agent Runtime Adapter
                  |
          WS / future runtime    execution plane
                  |
       source / tools / xlf / artifacts
```

依赖规则：

- Service 永远不主动连接 Node；浏览器和 Hermes 也不连接 Node；
- Node 不开放本机业务端口，只主动访问 Service 的 HTTPS 入口；
- 三模块不共享源码、Entity、DTO、数据库、生成类或构建产物；
- 模块间只依赖 `doc/contracts/` 中由架构 owner 维护的版本化合同；
- Node 不理解 SPM 业务细节，Agent Runtime 根据 `executionProfile` 执行具体工作流；
- Runtime 专用命令、Session 字段和原始事件不得进入 Service 或 Web 模型。

## 4. 网络模型

工作电脑只要求出站 HTTPS：

1. Service 先把命令持久化；
2. Node 主动 poll，并在本地持久化后 ACK；
3. Node 的事件先写本地 outbox，再主动 POST；
4. Service ACK 后 Node 才清除 outbox；
5. 断网期间正在执行的 Agent 可以继续，恢复后按本地顺序重放控制事件。

MVP 使用普通 HTTPS 短轮询和 POST，不以入站端口、WebSocket、SSE、固定公网 IP 或端口转发作为正确性前提。Node 必须支持显式企业 HTTP/HTTPS 代理、TLS 信任配置、连接预检和指数退避。生产入口必须是稳定域名；会变化的 Quick Tunnel 只作临时验证。

## 5. 数据所有权

Service 允许保存：稳定 ID、不可变 Mission 合同及 digest、Run/Interaction/Node 状态、短阶段、短进度、短结果、命令、幂等记录、通知投递状态和必要审计。短文本最多 800 字符，单个协议消息最多 64 KiB。

Node 本地保存：Mission 快照、控制命令日志、Runtime Session/handle、Turn、checkpoint、完整对话、tool call、文件 diff、原始事件、stderr、完整日志、完整结果、绝对路径、xlf 资料和 outbox。

Service、Hermes 和 Web 禁止接收或保存：Runtime Session、resume token、本机绝对路径、源码、完整对话、原始 Runtime 事件、完整日志、diff 和产物。

Sites Web 不拥有业务数据；D1/R2 保持 `null`，浏览器不持有 Service credential，不使用浏览器存储保存业务状态。

## 6. Run、Session 与 message

Run、Agent Session 和 message 是不同概念：

- **Run**：Mission 的一次业务执行，Service 与 Node 共同识别；
- **Agent Session**：Node 本地持有的 Runtime 会话，一个 Run 在 MVP 中恰好对应一个 Session；
- **message**：OpenCode 原生会话输入/输出；一个 Mission 通常只有一次初始 prompt，真实追问才增加 message。

Service 只保存 Run 状态和 `resumable` 投影，不保存 Session/message 标识。Node 串行化同一 Run 的命令、Runtime 事件、超时和取消，先持久化的终态决定本地结果，后到事件只作本地审计。

## 7. 可信完成

只有同时满足以下条件，Run、Mission 和 WorkItem 才能完成：

1. OpenCode 原生 Session 已从 `busy/retry` 收敛到 `idle`，且 Node 已完成 status/message reconciliation；
2. 独立 AcceptanceEvaluator 已用本地可核验证据检查不可变 Mission；
3. `runtimeOutcome=SUCCEEDED` 且 `acceptanceStatus=PASSED`；
4. Node 已在本地持久化终态；
5. Service 接受该状态转换。

`idle`、HTTP 204、Session 正常存在、普通完成文本、断网或超时都不能单独替代业务验收。缺少验收证据或恢复身份不明确时进入 `uncertain`，不得自动新建第二个 Agent Session。

## 8. 安全边界与不做项

- 首轮单 Node、单活动 Run、单 Agent Session、多 Turn；
- 不做多 Agent 协作、同一工作区并发 Run、跨 Node 迁移和自动重试副作用；
- Node 不实现文件工具、远程文件 API、通用 shell API或工作区沙箱；
- Service 不保存 Runtime 数据，不直接运行任务，不内置微信身份；
- Hermes 不拥有状态机，不绕过 Service 控制 Node，不自动批准风险操作；
- Web 保持只读，不解决 Interaction；
- Kanban 不进入目标设计；
- 公司电脑只运行经 Gate 批准的 Node 版本，不开发、提交或推送 Workbench 源码。

详细业务流程见 `workflow.md`，冻结范围见 `design-freeze-v0.3.md`，跨模块消息语义见 `contracts/control-loop-v2.md`。
