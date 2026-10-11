# 后端基础工程

使用 Spring Boot 3.5.16、MyBatis-Plus 3.5.17、MySQL 与 Flyway，Java 编译目标为 21。阶段 2 已实现企业账号登录、JWT、RBAC、会话撤销和部门/申请资源访问校验。

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

通过 `POST /api/auth/login` 提交 `username`、`password` 获得令牌，后续请求设置 `Authorization: Bearer <accessToken>`。详见 [阶段 2 接口与权限](../docs/阶段2接口与权限说明.md)。当前前端尚未对接登录。

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

参考：[Spring Boot 3.5 环境要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/)、[Flyway MySQL 支持](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)。
