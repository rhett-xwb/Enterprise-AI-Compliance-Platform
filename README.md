# Enterprise AI Compliance Platform

企业智能法律合规与审批平台。阶段 1–6 已完成，支持文档与版本管理、OCR、中文 Embedding、Qdrant 本地持久化、条款切片、索引重建与授权制度原文检索。阶段 7–9 已实现带引用问答、差旅申请、预审、可配置人工审批与处理历史，真实生成模型配置与质量验收待完成。新制度文件保存为草稿，经解析、索引和人工核对后发布。

| 目录 | 职责 |
| --- | --- |
| frontend | Vue、Vite、Vue Router、Element Plus 前端 |
| backend | Spring Boot、MyBatis-Plus、Flyway 业务后端 |
| ai-service | FastAPI 内部解析、PaddleOCR、Embedding 与 Qdrant 服务 |
| docs | 开发计划、数据库设计、接口规范和开发日志 |
| docker | MySQL 部署配置，完整应用部署后续完善 |
| scripts | 阶段验收与文档工具 |

启动：[后端说明](backend/README.md)、[前端说明](frontend/README.md)。

设计：[开发计划](docs/开发计划书.md)、[数据库设计](docs/数据库设计.md)、[阶段 1 接口](docs/阶段1接口规范.md)。

重大改动同步维护 [开发日志](docs/DEVELOPMENT_LOG.md)。

缓存与异步：[阶段 10 说明](docs/阶段10接口与运维说明.md)、[验收记录](docs/阶段10验收记录.md)、[学习笔记](My_Learning/stage10_note.md)。新增事务 outbox、RabbitMQ 解析/索引、幂等与故障恢复、管理员任务监控及可选解析结果 Redis 缓存。默认关闭 ASYNC_TASKS_ENABLED/SOURCE_CACHE_ENABLED，兼容原处理模式。工程验收：`pwsh -NoProfile -File scripts/verify-stage10.ps1`；真实中间件、缓存性能对照与生产故障演练待验证，不宣称阶段十完整运行验收完成。

认证：[阶段 2 接口与权限](docs/阶段2接口与权限说明.md)、[阶段 2 验收](docs/阶段2验收记录.md)。完整验证：`pwsh -NoProfile -File scripts/verify-stage2.ps1`。

前端：[阶段 3 验收](docs/阶段3验收记录.md)。前后端完整验证：`pwsh -NoProfile -File scripts/verify-stage3.ps1`，首次使用先按前端说明安装 Chromium。

制度：[阶段 4 接口](docs/阶段4接口与权限说明.md)、[验收记录](docs/阶段4验收记录.md)、[学习笔记](My_Learning/stage4_note.md)。包含制度流程的完整验证：`pwsh -NoProfile -File scripts/verify-stage4.ps1`。

解析：[服务启动](ai-service/README.md)、[阶段 5 接口](docs/阶段5接口与权限说明.md)、[验收记录](docs/阶段5验收记录.md)、[学习笔记](My_Learning/stage5_note.md)。完整验证：`pwsh -NoProfile -File scripts/verify-stage5.ps1`。解析成功为 PARSED，真实索引完成后才推进 READY。

检索：[阶段 6 接口](docs/阶段6接口与权限说明.md)、[验收记录](docs/阶段6验收记录.md)、[学习笔记](My_Learning/stage6_note.md)。完整验证：`pwsh -NoProfile -File scripts/verify-stage6.ps1`，首次安装依赖并执行 `.cache/ai-venv/Scripts/python.exe scripts/prepare-embedding.py` 准备固定模型。管理入口支持索引与重建，员工使用“制度原文检索”查看实际条款与来源。

问答：[阶段 7 接口与模型配置](docs/阶段7接口与权限说明.md)、[验收记录](docs/阶段7验收记录.md)、[质量问题集](docs/阶段7质量评估.md)、[学习笔记](My_Learning/stage7_note.md)。完整工程验收：`pwsh -NoProfile -File scripts/verify-stage7.ps1`，生成端使用受控契约夹具，不替代真实模型质量评估。默认关闭问答；配置 QA_* 环境变量后启用。

差旅申请：[阶段 8 接口与权限](docs/阶段8接口与权限说明.md)、[验收记录](docs/阶段8验收记录.md)、[学习笔记](My_Learning/stage8_note.md)。工程验收：`pwsh -NoProfile -File scripts/verify-stage8.ps1`。支持本人草稿、附件、确定性规则、带引用预审、修改后失效及当前内容快照提交。预审生成复用 QA_*，未启用时明确无法判断；TRAVEL_MAXIMUM_AMOUNT 默认 0，不设置硬编码业务金额上限。

人工审批：[阶段 9 接口与权限](docs/阶段9接口与权限说明.md)、[验收记录](docs/阶段9验收记录.md)、[学习笔记](My_Learning/stage9_note.md)。验收：`pwsh -NoProfile -File scripts/verify-stage9.ps1`。管理员先在“审批流程配置”发布金额区间与有序单人节点；员工提交后进入“审批中”，审批人查看固定材料并填写同意、拒绝或退回意见。支持本人撤回、重新编辑/预审/提交、历史快照与并发防重。工程验收关闭生成端，明确保留 UNKNOWN，不依赖远程模型。旧 SUBMITTED 可撤回重提；不支持会签、并行与 BPMN。
