---
status: current-evidence
authority: release-candidate-evidence
source_ref: v0.2.0-mvp1-rc1
owner: integration
superseded_by: null
last_verified: 2026-08-19
---

# E2E-1 真实 WS 尝试

## 已验证部分

- 临时自签 HTTPS Service、真实 Service JAR、真实 Node JAR、独立 Node credential 和临时工作区均正常启动。
- Client API 成功创建不可变 Mission，Node 成功 hello、heartbeat、poll、持久化 assignment 并 ACK。
- 首次运行发现 Windows 通过 `ws.cmd` 启动时，多行 prompt 只把第一行传给 `ws.exe`。Node 的 WS Adapter 已改为单行 prompt，并增加测试确保 objective、acceptance、授权摘要和 Mission digest 全部存在且不含 CR/LF。
- 修复后从进程命令行核验，完整 Mission 合同已传入真实 `ws.exe`；没有授权工具或副作用。

## 未通过部分

真实 WS 进程在 180 秒观察窗口内没有输出任何 JSON、stderr、Session ID、进度或终态，且未自行退出。Node 因而只能保持本地执行中，Service 保持 `in_progress`。测试 harness 随后停止了 Node、WS 和 Service 进程。

此结果属于 Runtime 环境阻塞，不是可信完成。它不满足真实 WS Gate，也不授权删除旧 Python、部署、安装或发布。

## 后续 Gate

在能够确认 WS CLI 的认证、模型连接和非交互 `run --format json` 输出正常后，使用相同无工具 Mission 重跑。只有收到 digest 匹配且 `acceptanceStatus=PASSED` 的结构化终态，才可把真实 WS E2E 标为通过。
