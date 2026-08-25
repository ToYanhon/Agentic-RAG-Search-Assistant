# 架构图表

本目录使用原生 `.drawio` 文件保存架构图，打开后可以继续编辑。图表对应关系如下：

| 文件 | 内容 | 对应文档 |
|---|---|---|
| `system-architecture.drawio` | 服务和基础设施部署拓扑 | [`docs/architecture.md`](../docs/architecture.md) |
| `request-and-auth-flow.drawio` | 用户请求、Agent 代理和双认证 | [`docs/api-contract.md`](../docs/api-contract.md) |
| `file-index-lifecycle.drawio` | 文件生命周期和索引通知 | [`docs/file-lifecycle.md`](../docs/file-lifecycle.md) |
| `data-model.drawio` | 数据存储分层和 owner 隔离 | [`docs/data-and-security.md`](../docs/data-and-security.md) |

## 编辑规则

- 使用 draw.io Desktop 或 diagrams.net 打开 `.drawio` 文件。
- 服务名、端口、认证 header 和 Redis key 必须与代码及 `docker-compose.yml` 一致。
- 图表只描述当前实现，不绘制已弃用的 Go 后端、组织、部门或 ACL 模型。
