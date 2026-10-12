# 数据库部署目录

阶段十新增 `middleware.compose.yml`：RabbitMQ 持久任务队列与可丢弃 Redis 缓存，独立随机凭据、回环端口、Rabbit 数据卷与健康检查。启动/故障处理见[阶段十运维说明](../docs/阶段10接口与运维说明.md)。本机没有 Docker，模板未执行容器启动验收。

阶段 1 提供 MySQL Compose 配置。应用、前端和 OCR 的完整容器化属于后续部署阶段。

在项目根目录复制 `.env.example` 为 `.env`，将 `DB_PASSWORD` 和 `MYSQL_ROOT_PASSWORD` 替换为不同的本地密码。若 3306 已被占用，修改 `MYSQL_PORT`，并同步调整 Java 的 `DB_URL`。

```powershell
docker compose --env-file .env -f docker/mysql.compose.yml up -d
docker compose --env-file .env -f docker/mysql.compose.yml ps
```

数据库端口仅映射到本机回环地址，初始化空数据卷时创建 `compliance_platform` 和 `compliance_app`。应用启动后 Flyway 负责建表。密码变更不会自动更新已经初始化的数据卷中的账号，应使用数据库账号管理操作。

```powershell
docker compose --env-file .env -f docker/mysql.compose.yml down
```

停止命令保留数据卷；不要随意删除数据卷。容器配置需要本机 Docker 环境，本阶段可使用独立 MySQL 验收脚本替代容器运行验证。
