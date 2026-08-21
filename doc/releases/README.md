---
status: authoritative
authority: release-process
source_ref: release-workflow-v1
owner: integration
superseded_by: null
last_verified: 2026-08-20
---

# 版本与发布流程

GitHub Release 只发布已经存在的不可变 `v*` 标签。候选版使用带后缀的版本，例如
`v0.2.0-mvp1-rc2`；正式版使用 `vMAJOR.MINOR.PATCH`。已经推送的标签不得移动、覆盖或复用，
失败的候选版通过递增 RC 编号修正。

## CI Gate

每个 Pull Request 与 `main` 提交必须通过：

- Java 21 Maven reactor 的完整 `verify`，覆盖 Service 与 Node 测试；
- Web 锁定依赖安装、lint、生产构建测试和生产依赖审计；
- 三个版本化 OpenAPI 合同的严格 lint；
- Service 与 Node 在 Windows 上的 Maven Wrapper smoke。

CI 只验证仓库内容，不持有服务器、Sites、Hermes 或 Node credential，也不访问生产数据。

## 发布 Gate

`.github/workflows/release.yml` 可由新 `v*` 标签触发，也可对一个已存在的精确标签手动触发。
工作流先确认标签指向当前提交，并确认根 POM、两个 Java 模块和 Web 的版本完全一致；随后重新执行
全部构建与测试，生成两个可执行 JAR 和 `SHA256SUMS`，最后创建 GitHub Release。带后缀的版本自动标记
为 prerelease。

GitHub Release 成功不自动授权或触发以下动作：

- 个人服务器部署或凭证变更；
- Sites 发布、访问策略变更或 D1/R2 操作；
- 公司电脑 Node 安装、升级或 Runtime 登录；
- 旧 D1、远端分支、已安装程序或其它外部资产删除。

上述动作继续使用独立 Gate，并在执行前核对精确 tag、提交 SHA、构建产物 SHA-256 和目标环境。

## 发布步骤

1. 在分支上同步四处版本：根 `pom.xml`、两个模块 `pom.xml` 和 `workbench-web/package.json`，并运行 CI。
2. 为候选版新增 `doc/releases/<tag>.md`，记录包含项、未满足 Gate 和验证证据。
3. 合并到 `main` 后创建新的 annotated tag；不得重打已有 tag。
4. 推送 tag，等待 Release workflow 成功，并保存 JAR 与 `SHA256SUMS`。
5. 生产部署、Sites 发布和 Node 安装分别获得 Gate 后，才使用该精确 Release 产物。
