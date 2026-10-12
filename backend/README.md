# 后端基础工程

使用 Spring Boot 3.5.16、MyBatis-Plus 3.5.17、MySQL 与 Flyway，Java 编译目标为 21。已实现企业账号登录、JWT、RBAC、会话撤销、部门/申请资源校验，以及阶段 4 制度文件与版本管理。

## 环境

- JDK 21+、Maven 3.6.3+。
- MySQL 8.0+，本次验收使用 8.0.46。
- 数据库与专用账号须事先创建；Flyway 在应用启动时创建表与种子元数据。
- 默认只监听 `127.0.0.1:8080`，正式业务接口需要 Bearer 令牌；临时 Basic 接口默认关闭。

## 数据库准备

可选择独立 MySQL 或使用 [数据库部署说明](../docker/README.md)。不要将迁移指向已有业务数据库。

在 MySQL 管理会话中执行以下示例，并用本地独立密码替换占位符：

```sql
CREATE DATABASE compliance_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'compliance_app'@'localhost' IDENTIFIED BY '<local-database-password>';
GRANT ALL PRIVILEGES ON compliance_platform.* TO 'compliance_app'@'localhost';
```

账号必须与实际连接来源匹配；例如容器网络应使用 Docker 创建的账号。阶段 1 应用账号负责迁移，需要此数据库内的 DDL 和 DML 权限，无需授予全局权限。后续可拆分迁移账号与运行账号。

## 启动与配置

Spring Boot 不会自动读取根目录 `.env`。`.env.example` 是配置说明及 Docker 示例；启动 Java 时通过进程环境变量或 IDE 运行配置注入参数。

```powershell
# PowerShell 7，在项目根目录执行。
$env:DB_USERNAME = 'compliance_app'
$env:DB_PASSWORD = Read-Host '本地数据库密码' -MaskInput
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
# 保存此密钥到本地私有运行配置；每次换密钥会使旧令牌失效，不要提交到仓库。
# 仅首次在无用户的数据库启动时开启：
$env:INITIAL_ADMIN_ENABLED = 'true'
$env:INITIAL_ADMIN_USERNAME = 'admin'
$env:INITIAL_ADMIN_PASSWORD = Read-Host '初始管理员密码 至少12字符' -MaskInput
# 下列连接仅用于本地无 TLS 的开发数据库。
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/compliance_platform?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
cd backend
mvn spring-boot:run
```

默认 JDBC 配置要求 TLS；真实部署应提供经过验证的 TLS 连接配置。本地无 TLS 示例不可直接作为生产配置。

访问 `GET http://127.0.0.1:8080/health` 可检查数据库连接。Flyway 失败会阻止应用正常启动；不要关闭迁移检查来绕过错误。

首次成功启动后，将 `INITIAL_ADMIN_ENABLED` 设为 `false` 并清除初始密码配置。已有用户时初始化器不会重置密码或创建额外管理员。原阶段 1 数据库若已有普通用户，需要由数据库管理人员受控授予一个现有用户 ADMIN 角色；初始化器不会自动提升已有账号。`JWT_SECRET` 必填，为至少 32 字节随机密钥的 Base64；缺失或长度不够会阻止启动。令牌默认有效 900 秒，可用 `JWT_TTL_SECONDS` 设置为 60–3600 秒。

通过 `POST /api/auth/login` 提交 `username`、`password` 获得令牌，后续请求设置 `Authorization: Bearer <accessToken>`。详见 [阶段 2 接口与权限](../docs/阶段2接口与权限说明.md)。前端已对接登录和制度管理。

### 文件存储

`DOCUMENT_STORAGE_ROOT` 指定持久化目录，默认 `data/documents`，相对路径依据 Java 进程工作目录解析。运行账号须有读写权限；生产使用固定绝对路径或持久卷，并将文件目录与 MySQL 一起备份。文件不放入静态资源目录，不允许公网直接读取，通过带 Bearer 的下载接口重新校验访问范围。

支持 PDF、DOCX、PNG、JPG 和 JPEG，最大 10 MiB；旧版 DOC 需先转换为 DOCX。上传版本保存为 DRAFT/PENDING，没有手动设置 READY 的业务接口。阶段 5/6 实现解析和索引后才能发布；发布必须有完整就绪文本块与索引。新版本替换、停用和删除保留原文件及审计，逻辑删除后访问接口返回 404。详见 [阶段 4 接口](../docs/阶段4接口与权限说明.md)。

### 临时本地 CRUD 模式

仅在本地验证基础读写时设置以下变量，然后重新启动应用：

```powershell
$env:BOOTSTRAP_ENABLED = 'true'
$env:BOOTSTRAP_USERNAME = 'bootstrap-admin'
$env:BOOTSTRAP_PASSWORD = Read-Host '临时管理员密码 至少12字符' -MaskInput
```

开启后，只允许临时内存管理员通过 HTTP Basic 调用 `/api/bootstrap/users`。管理员密码要求至少 12 字符且不超过 72 UTF-8 字节，应用只接受 `127.0.0.1` 或 `::1` 监听。普通数据库用户不能通过此认证登录。验证结束将开关设为 `false` 并重启；不要将此临时模式暴露到公网或用于正式业务。

此模式仅保留阶段 1 回归验收。正式接口使用请求头 Bearer 认证，不从 Cookie 自动获取凭据；如后续改为 Cookie 认证，需要同步增加 CSRF 防护。

## 构建与验收

```powershell
cd backend
mvn package
java -jar target/compliance-platform-0.1.0-SNAPSHOT.jar
```

普通 `mvn test` 运行配置约束单元测试；未配置 `TEST_DB_URL` 时真实 MySQL 集成测试会跳过，不代表数据库已通过验收。

推荐从项目根目录使用完整验收脚本：

```powershell
pwsh -NoProfile -File scripts/verify-stage2.ps1
```

脚本使用本机 `mysqld.exe` 新建独立缓存数据目录，随机选择回环端口，初始化专用测试库与随机密码，执行迁移与集成测试，启动打包后的应用完成真实 HTTP CRUD 和默认拒绝检查，最后停止自己启动的进程。不会修改已有 MySQL 服务；需要进程、网络访问权限。证据保留在 `.cache/stage1-*` 和 `backend/target/surefire-reports/`，均不入库。测试库只用于本机临时验证，初始化过程无 root 密码，不得用作正式数据库。

阶段 2 脚本复用阶段 1 的隔离环境，额外创建空认证验收库，检查首次管理员初始化、登录、角色即时变更、退出撤销、重启后会话与密码保留，并检查日志中是否存在随机凭据和令牌。输出 `STAGE2_VERIFIED` 表示这些检查通过。

可手动配置 `TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD` 后运行 `mvn verify`；测试仅接受 `127.0.0.1` 上名为 `compliance_platform_test` 的数据库，数据用事务回滚，迁移表和种子元数据保留。

## 代码结构

```text
src/main/java/com/enterprise/compliance/
├── ComplianceApplication.java
├── common/        统一响应和异常处理
├── config/        安全开关 请求日志 MyBatis 分页
├── auth/          JWT 登录 账号状态 会话撤销 初始管理员
├── access/        部门与申请资源访问边界
├── document/      文件存储、上传校验、制度版本、发布与审计
├── health/        数据库健康检查
└── user/
    ├── controller/
    ├── service/
    ├── mapper/
    ├── entity/
    └── dto/
src/main/resources/
├── application.yml
└── db/migration/   Flyway 有序迁移
```

更多说明：[数据库设计](../docs/数据库设计.md)、[接口规范](../docs/阶段1接口规范.md)。

阶段 5 增加持久解析任务与正文来源接口，使用 V5 迁移。Python 服务启动后设置 PARSER_ENABLED=true、PARSER_URL 与共享 PARSER_SECRET，两个服务使用相同绝对 DOCUMENT_STORAGE_ROOT；配置和启动见 [服务说明](../ai-service/README.md)。解析成功仅为 PARSED。完整验证：`pwsh -NoProfile -File scripts/verify-stage5.ps1`；[接口与权限](../docs/阶段5接口与权限说明.md)。

阶段 6 使用 V6 增量迁移，增加索引代次、来源偏移和索引运行状态。Java 负责条款切片、发布状态与实时权限；独立 Python 向量服务负责中文 Embedding 和 Qdrant。默认 `RETRIEVAL_ENABLED=false`，开启前按 AI 服务说明准备固定模型并配置独立内部密钥。真实索引完成推进 READY，重建期间暂停该版本检索。新增接口见 [阶段六说明](../docs/阶段6接口与权限说明.md)，完整验收 `pwsh -NoProfile -File scripts/verify-stage6.ps1`。没有引入 Spring AI/LangChain4j，检索使用明确的内部 HTTP 契约。

阶段 7 使用 V7 增量迁移保存单次制度问答及来源快照。默认 `QA_ENABLED=false`；先启用阶段六检索，再配置完整 `QA_COMPLETIONS_URL`、`QA_MODEL`、`QA_API_KEY` 与 `QA_TIMEOUT_SECONDS` 后开启。远程模型地址必须 HTTPS，本地回环可 HTTP。Spring Boot 不自动加载根目录 `.env`，请将所需值注入启动进程环境。兼容协议、结果状态、历史 ACL 和配置见 [阶段七说明](../docs/阶段7接口与权限说明.md)。完整工程验收：`pwsh -NoProfile -File scripts/verify-stage7.ps1`；使用本地受控模型夹具，真实模型质量需按 [问题集](../docs/阶段7质量评估.md) 人工核对。历史记录仅本人可见，来源权限撤回时隐藏整段答案。

阶段 4 完整验收：`pwsh -NoProfile -File scripts/verify-stage4.ps1`，首次先按前端说明安装 Chromium。脚本提供独立文件目录，回归认证与用户 CRUD，并验证上传、授权下载、事务回滚、并发发布、历史与停用。发布浏览器流程的 READY 和索引仅由受控隔离数据库夹具建立，不表示 OCR 或向量服务已实现。

参考：[Spring Boot 3.5 环境要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/)、[Flyway MySQL 支持](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)。

## 阶段八差旅申请

`/api/travel-requests` 要求 `approval:submit`，所有操作限本人。V8 增量迁移扩展附件并保存提交快照；详细接口与状态见 [阶段八接口说明](../docs/阶段8接口与权限说明.md)。预审复用 QA_* / RETRIEVAL_*，模型关闭时仍可执行表单检查并保存“无法判断”记录。可选 `TRAVEL_MAXIMUM_AMOUNT` 默认 0 关闭业务上限，金额固定人民币。提交后锁定材料并留痕；附件沿用私有 DOCUMENT_STORAGE_ROOT，不解析正文。

## 阶段九人工审批

V9 增量迁移增加发布指针、实例路线快照和防重约束，提交记录允许按不同内容版本留存多份。`/api/workflows` 由方法权限与任务归属共同控制；ADMIN 先发布差旅配置，未配置时提交返回 409。新提交同事务创建首个任务并返回 IN_REVIEW，金额区间与审批人不由员工指定。旧 SUBMITTED 不自动启动新流程，可本人撤回并重提。完整验收：`pwsh -NoProfile -File scripts/verify-stage9.ps1`，隔离 MySQL、全部后端回归与审批浏览器流程，生成端关闭。接口/状态/有限方案选型见 [阶段九说明](../docs/阶段9接口与权限说明.md)。

## 阶段十缓存与异步

V10 增量增加 document_async_job，Spring Boot BOM 管理 AMQP/Redis 依赖。默认 ASYNC_TASKS_ENABLED=false、SOURCE_CACHE_ENABLED=false，无需连接中间件。启用异步时解析与索引原子写 outbox，Rabbit 持久投递与 confirm/returned 检查，消费端有限重试与租约恢复；原解析轮询关闭。索引仍返回 state，PROCESSING 包含排队。Redis 仅缓存已成功解析的不可变来源，所有读取先重新授权，缓存故障回源数据库。详见[阶段十接口与运维](../docs/阶段10接口与运维说明.md)。工程验收 `pwsh -NoProfile -File scripts/verify-stage10.ps1`；真实中间件与性能测量待验证。
