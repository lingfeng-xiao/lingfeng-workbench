# v0.2 数据边界

## 1. 判定顺序

每一项数据在产生、接收或上传前必须显式声明类别。未声明、分类有争议或无法证明安全的数据，一律视为 **local-only**。系统不承诺自动脱敏；边界责任不能由“稍后清洗”替代。

## 2. 四类数据

| 类别 | 业务含义 | 允许位置 | 典型内容 | 禁止事项 |
|---|---|---|---|---|
| control | 驱动和追踪工作的最小业务事实 | D1；经业务 API 读写 | WorkItem、运行状态、审批状态、幂等键摘要、节点在线摘要、发布追溯引用 | 正文、源码、日志、凭证混入控制摘要 |
| cloud-safe | 符合封闭 allowlist、可进入 R2 的 Workbench 自身产物 | R2；D1 只存索引与分类声明 | 仅限 Workbench 自身设计文档、Workbench 自身测试报告、Workbench 页面截图、合成 Fixture、用户明确确认安全的导出文件 | allowlist 外内容、未取得用户明确确认的导出、Node 发布包进入 R2 |
| local-only | 只属于某一电脑/工作区的上下文 | 对应 Node 本地 | 公司代码及 diff、原始日志/SQL/数据库导出、客户或生产数据、完整业务测试或构建报告、Runtime 原始事件与对话、办公电脑绝对路径、工作区/会话上下文及任何未明确分类数据 | 进入 D1/R2；跨电脑同步；随任务迁移或换电脑续跑 |
| secret | 可授予能力或泄露身份的敏感值 | 专用秘密存储或进程短时内存 | token、私钥、Sites/D1/R2 凭证、签名私钥 | 写入仓库、D1、R2 产物、日志、审批摘要；下发给办公 Node 的 Sites/D1/R2 凭证 |

## 3. 事实源与访问路径

- D1 是 control 数据的唯一事实源；不得以 Node SQLite、浏览器缓存、消息记录或 Kanban 作为第二事实源。
- R2 不是业务数据库，只承载 cloud-safe 封闭 allowlist 内的不可变或版本化产物；D1 保存其分类、摘要、哈希、版本和追溯引用。
- GitHub 私有仓库是源码事实源。Node 签名发布包只走“云 CI→正式 GitHub Release→Hermes→Node”链路，R2 不承担 Node 包存储、镜像或分发角色。
- Sites 版本与 Node Release 分别追溯到 commit、云 CI run 和用户 Release Gate，不互相充当事实源。
- 所有产品读写只走版本化业务 API。页面、Hermes 和 Node 都不得绕过业务规则直接访问底层表或对象。
- Node 只与 Hermes 建立机器通道。Hermes 代表 Node 调用 Sites 业务 API；Node 不直连 Sites、D1 或 R2。

## 4. 对象级边界

| 对象/字段 | 类别 | 云端保存范围 | 本地保存范围 |
|---|---|---|---|
| WorkItem / Proposal | control | 标题、目标、AC 摘要、状态、Gate 引用 | 可保存本地工作材料，但不反向同步正文 |
| AgentRuntime | control | 类型、能力声明、健康摘要、所属 Node、版本 | runtime session 细节和本地进程材料 |
| Run / Interaction | control | 状态、时间、检查点、短摘要、明确选项 | 完整输入输出、原始日志、会话上下文 |
| Artifact 元数据 | control | 分类、所有者、哈希、大小、R2 key、来源 commit/run | local-only 文件只记本地引用，不生成云 key |
| Artifact 内容 | cloud-safe 或 local-only | 仅封闭 allowlist 内的 cloud-safe 内容进入 R2；Node 发布包不属于 R2 Artifact | 公司代码及 diff、原始日志/SQL/数据库导出、客户或生产数据、完整业务测试或构建报告、Runtime 原始事件与对话、办公电脑绝对路径及未分类数据永远留在产生它的 Node |
| Outbox | local-only + 最小 control 回执 | D1 仅保存已接收消息 ID、状态结果 | 待发消息、重试计数、顺序与本地证据引用 |
| 发布追溯 | control | commit、CI run、包哈希、签名验证结果、Gate、回滚版本 | Node 保存当前/上一版本及验证记录 |
| Node 签名发布包 | Release 资产（非 R2 Artifact） | 正式 GitHub Release；Hermes 只做经 Gate 的原样镜像/转交 | Node 仅保留 current/previous 已验证版本 |
| 凭证/私钥 | secret | 不进入业务表或产物；只允许受控秘密存储 | Node 不持有 Sites/D1/R2 凭证；签名私钥不下发 |

## 5. 电脑隔离

- WorkItem 可指定目标 Node，但不会携带另一台电脑的工作区、会话或任务执行上下文。
- 同一 WorkItem 不得换电脑新建 Run 继续。跨电脑继续必须新建独立 WorkItem；上下文、Mission、Run、会话、文件和本地引用均不迁移、不同步。
- 云端 `local_workspace_ref` 只允许保存绑定唯一 Node 的 Node-scoped 不透明引用 ID，不得包含或推导真实路径，也不得被其他 Node 解析；办公电脑绝对路径只留在对应 Node 本地，不能进入 D1、R2、日志或报告。
- 办公电脑绝对路径只能留在对应 Node 本地，禁止进入 D1、R2、云端日志、构建/测试报告或其他云端产物。
- Node 离线时，云端仅显示最后确认的 control 状态；不得猜测本地执行结果。

## 6. 上传和下载 Gate

cloud-safe 上传必须先命中封闭 allowlist，并同时具备：分类声明、来源、哈希、大小、所有者/关联对象、允许上传的用户或规则 Gate。用户明确确认安全的导出文件还必须绑定该精确文件与确认记录。任何一项缺失即拒绝；自动摘要或自动脱敏不能把 local-only 转成 cloud-safe。

数据边界相关的三项不可替代人工安全 Gate 仍按产品总合同执行：用户决定精确 Proposal 是否接受；用户决定精确 Release 是否生产发布；任何破坏性数据库迁移、权限扩大或数据边界改变发生前，用户必须针对该精确变更再次单独确认。设计确认和 PR 合并只能作为额外治理 Gate，不能替代第三项再确认，也不能替代 Proposal 或 Release 的用户决定。

Node 发布包必须从正式 GitHub Release 由 Hermes 原样镜像/转交，且在公司电脑安装前完成来源、版本、manifest、哈希和签名校验。不得上传 R2 后另建分发链。签名算法留待实现设计选择，本基线只规定可验证结果与追溯字段。

## 7. 删除与回滚边界

- 删除 control 数据不得顺带删除 local-only 文件；两者生命周期独立。
- 删除 R2 对象前必须先确认没有有效发布或业务引用，并保留可审计的 control 事件。
- 应用回滚不回滚或覆盖 Node 本地上下文；Node 回滚也不改变 D1 业务历史。
- 备份恢复以 D1 逻辑一致性为目标；频率、工具和保留期不在本设计中预先指定。
