# 阶段十学习笔记：把耗时工作可靠地交给队列

日期：2026年10月12日  
主题：事务 outbox、RabbitMQ、至少一次投递、幂等、租约、失败恢复与 Redis 缓存边界。

## 1 阶段目标与生活场景类比

异步处理是先登记工作，再由后台执行，让员工不用一直等 OCR/索引请求结束；缓存是暂存已读过的结果，减少重复加载成本。阶段十先保护登记与处理的一致性，再提供可选缓存。

生活类比：法务把扫描文件交给资料室，先在登记本写下“这份文件需要识别”。快递员再把任务单送给处理窗口。快递员休息或窗口故障，登记本还在，工作人员可以补送或重试；即使两张相同任务单都送到，也只能归档一份结果。

Java 类比：Spring `@Transactional` 保证本地 MySQL 事务；`RabbitTemplate` 把工作送到另一套系统。数据库提交和网络发送没有天然的共同事务，所以不能假定在 Service 内顺手发消息就可靠。

**有什么用**：用户能看到排队与失败状态，后台故障可恢复，重复投递不生成重复来源或新向量集合。

## 2 为什么按这个顺序开发

```text
阶段五：真实提取 + 解析任务 + run_token
阶段六：切片 + generation + 向量 upsert + 授权过滤
阶段九：人工审批与审计闭环
阶段十：持久任务账本 → 事务 outbox → MQ → 幂等与恢复 → 缓存边界 → 监控
阶段十一：真实中间件联调、性能与完整部署演示
```

先有可靠业务状态，再异步化；否则只是把同步错误放到后台。先建立 outbox，才知道 broker 故障时哪份工作还没送到；先定义缓存边界，才能判断权限变化后旧数据是否安全。

本阶段完成了代码、任务管理页面、配置模板和工程验收准备。实际验证结果以[阶段十验收记录](../docs/阶段10验收记录.md)为准。真实 RabbitMQ/Redis 与缓存延迟对照尚未验收，不把客户端契约测试当成真实中间件上线或性能提升。

## 3 outbox 是什么，为什么不能只在事务结束后发消息

outbox 是保存在同一数据库事务里的“待发送登记”。本项目用 `document_async_job` 同时记录工作和投递状态。

| 做法 | 故障窗口 | 项目处理 |
| --- | --- | --- |
| 事务内先发 MQ 再提交 | MQ 收到但 MySQL 回滚，出现无效任务 | 不采用 |
| 提交后直接发送一次 | 提交成功后进程退出，任务没送出 | 仅 afterCommit 回调也不够可靠 |
| 任务 + outbox 同一事务 | 回滚时一起消失，提交后可补发 | AsyncDocuments + AsyncJobs |

例子：法务点击“开始解析”。`AsyncDocuments.parse()` 先调用 `ParsingStore.enqueue()`，再保存 `parse:<versionId>` job。两次写入都加入外层 Service 事务。提交后 `RabbitTasks.dispatch()` 才能看到记录并发送消息。

Java 类比：外层 `@Transactional` 像一个包含两次 DAO 写入的普通业务事务；RabbitMQ 发送由另一段定时任务执行，不把外部网络请求塞进原事务。

**后续怎么用**：将来邮件通知、索引刷新、其他耗时任务也可采用相同模式，但要定义各自的幂等键与结果提交规则。

阅读入口：[AsyncDocuments.java](../backend/src/main/java/com/enterprise/compliance/async/AsyncDocuments.java)、[V10 迁移](../backend/src/main/resources/db/migration/V10__async_jobs.sql)。

## 4 confirm、ACK 与至少一次投递

publisher confirm 是 RabbitMQ 告诉发送方“我接收了消息”；consumer ACK 是消费者告诉 RabbitMQ“这次处理已经持久保存，可以移走消息”。两个确认分别保护投递和消费，不等同于业务执行一次。

生活类比：快递员拿到窗口签收单，与窗口办完事情后在登记本盖章，是两步不同动作。窗口已经签收，但快递员没收到回执，会补送同一任务。

项目发送持久消息、使用 durable 队列，收到正 confirm 且没有 returned 才写 dispatched_at。发生连接故障仍保留 outbox。已经确认但长期未消费的 PENDING 允许补发，意味着系统主动接受“同一件事可能送来多次”。

消费端只收 job id 与 message token，不把正文和密钥放进消息。非法消息进入死信队列，正常业务失败记录到 MySQL 并按规则重试。

**有什么用**：消息没确认时能重送，重送不需要猜测旧消息是否已执行。

阅读入口：[RabbitTasks.java](../backend/src/main/java/com/enterprise/compliance/async/RabbitTasks.java)。原理参考：[RabbitMQ 消息确认](https://www.rabbitmq.com/docs/confirms)。

## 5 幂等怎样工作

幂等表示重复请求产生相同业务效果。它不表示消息只投递一次，也不表示 Java 方法永远只调用一次。

| 保护 | 作用 | 项目例子 |
| --- | --- | --- |
| task_key 唯一 | 重复请求不创建同一件工作两份账本 | parse:版本ID |
| job 状态 + 行锁 | 两个消费者不能同时认领 | 只从 PENDING 进入 PROCESSING |
| message_token | 旧消息不能认领新重试 | 每轮重试换 token |
| parser run_token | 旧 OCR 响应不能覆盖新尝试 | ParsingStore.current() |
| generation + 稳定 vector keys | 同一索引重试复用向量位置 | payload 持久保存 IndexStore.Work |
| MySQL 当前权限与版本 | 已存在的旧向量也不能成为有效答案 | RetrievalStore.keys()/resolve() |

索引例子：第一次向量 upsert 成功，Java 写数据库前进程退出。第二次处理从 job payload 取回同一 generation/keys，再 upsert 同一组向量；它更新相同位置，不额外生成另一套 keys。只有新的重建才创建新代次。

Java 类比：不是依靠单机 `synchronized`；认领发生在 MySQL 事务和行锁中，因此多个 Java 实例共享同一份状态。分布式系统仍可能重复外部调用，靠稳定键让外部写入可重复。

**后续怎么用**：排查重复索引时同时查 job、generation 和 keys；仅检查前端按钮是否禁用，不能证明后台幂等。

## 6 租约与故障恢复

租约是带有效期限的工作占用：消费者认领后有一段执行时间；超过期限就允许系统判断它可能已退出。不是无限持有数据库锁等待 OCR。

项目 job 租约 600 秒，索引调用上限 180 秒，异步解析配置不超过 500 秒。业务任务和结果用短事务提交，网络调用在事务外。

状态流程：

```text
PENDING → PROCESSING → SUCCEEDED
               ↓ 临时失败，累计少于三次
           PENDING（落库等待后重送）
               ↓ 达到自动次数/永久失败
             FAILED → 人工重新排队（累计最多五次）
```

恢复先检查结果：如果来源或索引已提交成功，只补上 job SUCCEEDED；否则按策略重新执行或记失败。解析恢复换掉运行令牌，迟到结果不会覆盖重试。删除、停用和索引代次变化仍由业务层检查。

broker 故障没有开始业务处理，因此不增加业务 attempts。加密文件这类故障不是等一会就好，直接失败供人工检查。超过五次不要直接改数据库清零，应修复输入/依赖再按治理流程处理。

**有什么用**：区分“任务没送出去”“业务服务暂不可用”“输入永久错误”“进程执行中退出”，避免无限重试。

阅读入口：[AsyncJobs.java](../backend/src/main/java/com/enterprise/compliance/async/AsyncJobs.java)、[AsyncProcessor.java](../backend/src/main/java/com/enterprise/compliance/async/AsyncProcessor.java)。

## 7 Redis 缓存为什么默认关闭

Redis 是独立于 Java 进程的键值存储。本阶段提供解析结果的短期缓存；是否启用需要真实测量。缓存访问还有网络、JSON 和权限查询成本，短文本未必更快。

生活类比：资料室把已核对的扫描正文放在近处备用，但每次取资料仍要查阅人的身份和权限。备用柜里的资料不能成为授权凭据。

项目只缓存已经 SUCCEEDED 的不可变解析结果。每次访问先执行 `requireManaged()` 并检查版本；键包含环境 namespace、版本ID与原文件 SHA-256；TTL 五分钟，过大正文不入缓存。Redis 故障回源 MySQL，缓存写入等读取事务提交后执行，回滚不向 Redis 留下数据。

Java 类比：这里是显式 cache-aside：Service 先授权，再读缓存，未命中查数据库，事务提交后填充。不能在 Controller 上随便套一个忽略用户的 `@Cacheable` 就认为权限安全。

目前没有缓存问答、检索结果、角色或当前制度集合，相关查询继续按数据库实时状态执行。以后若缓存答案，至少要区分访问范围、有效制度/索引版本、问题和模型配置，并提供失效机制。

**何时使用**：相同授权解析正文被反复读取，且实测延迟/数据库成本可下降时。低频或小正文不必默认引入额外运行成本。

**后续怎么用**：运行[缓存测量脚本](../scripts/measure-stage10-cache.ps1)，在相同输入下比较关闭/开启的 median/P95/mean 与命中率，再测权限撤销、删除和 Redis 断开。脚本已交付，本次没有真实 Redis 测量结果。

阅读入口：[SourceCache.java](../backend/src/main/java/com/enterprise/compliance/async/SourceCache.java)、[ParsingStore.sources()](../backend/src/main/java/com/enterprise/compliance/document/ParsingStore.java)。

## 8 管理监控与代码阅读顺序

管理员“文档处理任务”页面展示最近任务、次数、错误与刷新恢复，失败且次数允许时可以重新排队。法务/员工不能访问监控接口；法务继续在制度详情观察解析与索引状态。

建议阅读：

1. V10 看 task_key、message_token、run_token、lease_until 与时间字段。
2. AsyncDocuments 看业务任务和 outbox 如何加入同一个事务。
3. RabbitTasks 看确认后才更新投递状态。
4. AsyncJobs 看 claim、finished、recover、retry。
5. AsyncProcessor 对照阶段五/六的原 Store，看外部调用与结果事务的边界。
6. SourceCache / ParsingStore 看授权先于缓存和 afterCommit 写入。
7. [DocumentTasksView.vue](../frontend/src/views/DocumentTasksView.vue)看页面如何表达运行状态。
8. [AsyncIntegrationTest.java](../backend/src/test/java/com/enterprise/compliance/AsyncIntegrationTest.java)和[AsyncContractsTest.java](../backend/src/test/java/com/enterprise/compliance/AsyncContractsTest.java)看故障场景与实际验证范围。

## 9 当前边界与下一阶段

实际执行 `scripts/verify-stage10.ps1` 最终退出码 0：隔离 MySQL 空库完成 V1–V10 迁移，70 个后端测试通过且无跳过；Chromium 9 个认证/监控/人工审批用例通过，另外 13 个其他阶段浏览器用例按配置跳过；前端构建与脚本/文档静态检查通过。已检查桌面和 390px 移动截图。

验收详情以[阶段十验收记录](../docs/阶段10验收记录.md)为准。工程验证使用真实隔离 MySQL，外部解析/向量与 Rabbit/Redis 客户端采用受控契约，不能证明真实 broker 重启、Redis TTL/断开重连、Qdrant 重复 upsert 或生产吞吐已验收。

下一阶段准备可运行中间件环境，执行真实文件异步 OCR/索引、重复消息、broker 重启、消费者退出、Redis 测量及权限失效，再串起员工预审与人工审批的完整演示。集中告警、任务归档、Rabbit/Redis 高可用和生产 TLS 也需要部署设计。

**后续怎么用**：阶段十一必须读取本阶段的待验证项，补足证据后更新新阶段资料；保留这份笔记的当时边界，避免把“已写代码”误记成“已生产验证”。
