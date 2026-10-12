# Enterprise AI Compliance Platform

企业智能法律合规与审批平台，包含制度文档管理、解析与向量检索、带引用问答、差旅申请、人工审批和可选异步任务处理。

| 目录 | 职责 |
| --- | --- |
| `frontend/` | Vue、Vite、Element Plus 前端 |
| `backend/` | Spring Boot、MyBatis-Plus、Flyway 业务后端 |
| `ai-service/` | FastAPI 文档解析、OCR、Embedding 与 Qdrant 服务 |
| `docker/` | MySQL、RabbitMQ、Redis 部署配置 |
| `scripts/` | 验收、模型准备及诊断工具 |
| `docs/` | 项目说明、开发计划、数据库设计和开发日志 |

从[后端启动说明](backend/README.md)、[前端启动说明](frontend/README.md)和[AI 服务说明](ai-service/README.md)开始。配置参考 [.env.example](.env.example)，Spring Boot 不会自动读取根目录 `.env`，请按后端说明注入环境变量。

项目进度与验证边界见[开发日志](docs/DEVELOPMENT_LOG.md)。真实生成模型质量、RabbitMQ/Redis 联调及生产性能验收仍有待完成；配置模板与契约测试不代表完整生产部署已验收。

本地凭据、上传文件、模型缓存和构建产物不纳入版本控制。
