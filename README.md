# Enterprise AI Compliance Platform

企业智能法律合规与审批平台。已完成阶段 1 数据库与工程基础、阶段 2 后端认证与权限；前端目前为演示脚手架，登录联调属于阶段 3。

| 目录 | 职责 |
| --- | --- |
| frontend | Vue、Vite、Vue Router、Element Plus 前端 |
| backend | Spring Boot、MyBatis-Plus、Flyway 业务后端 |
| ai-service | 后续 FastAPI 与 OCR 服务，目前为规划目录 |
| docs | 开发计划、数据库设计、接口规范和开发日志 |
| docker | MySQL 部署配置，完整应用部署后续完善 |
| scripts | 阶段验收与文档工具 |

启动：[后端说明](backend/README.md)、[前端说明](frontend/README.md)。

设计：[开发计划](docs/开发计划书.md)、[数据库设计](docs/数据库设计.md)、[阶段 1 接口](docs/阶段1接口规范.md)。

重大改动同步维护 [开发日志](docs/DEVELOPMENT_LOG.md)。

认证：[阶段 2 接口与权限](docs/阶段2接口与权限说明.md)、[阶段 2 验收](docs/阶段2验收记录.md)。完整验证：`pwsh -NoProfile -File scripts/verify-stage2.ps1`。
