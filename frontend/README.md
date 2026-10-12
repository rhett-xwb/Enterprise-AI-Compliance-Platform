# 前端启动与业务入口

Vue 3、Vite、Element Plus、Vue Router 和 Axios。前端接入真实认证及制度接口，包含登录、工作台、角色菜单、用户查询、制度上传、版本管理、范围编辑与授权下载。

## 本地启动

先按 [后端说明](../backend/README.md) 启动后端并配置账号。项目没有共享默认密码。

```powershell
cd frontend
npm ci
Copy-Item .env.example .env.local
npm run dev
```

默认前端地址为 http://127.0.0.1:5173，`/api` 代理到 http://127.0.0.1:8080。后端端口变化时在 `.env.local` 设置 `API_PROXY_TARGET` 并重启 Vite。该配置仅用于本地代理，不包含密钥。

```powershell
npm run build
npm run preview
```

正式部署将 `dist/` 作为静态资源，并在同源网关反向代理 `/api` 到后端、启用 HTTPS。Vite preview 只用于本地预览。使用 Hash 路由，不需要业务路由回退规则。

## 会话与访问

- 登录调用 `/api/auth/login`，随后调用 `/api/auth/me` 获取当前身份。
- Bearer 令牌保存在本标签页的 sessionStorage，刷新时恢复；密码不持久化。令牌不是权限依据，页面切换重新读取服务端身份。
- 401 清理会话并返回登录页；403 提示无权限；网络或服务异常提供重试。当前无刷新令牌，到期重新登录。
- 退出调用后端撤销会话，成功后清理本地令牌；网络失败保留会话以便重试。
- ADMIN 且具备 `user:manage` 可进入用户查询；其他业务入口按权限集合显示，多角色取权限并集。前端菜单不替代后端校验。
- 制度查询展示当前有效且授权的版本；制度管理提供上传、版本、范围、发布、停用和删除。法务管理本人制度，管理员管理全部制度。
- 新上传版本为草稿，完成解析和索引后才能发布；没有人工标记 READY 的入口。申请、审批及合同页面仍待开放。

## 隔离验收

依赖本机 MySQL、JDK 和 Maven；脚本自动创建独立数据库，生成随机验收密码并在结束时停止所创建的服务。

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = (Join-Path (Resolve-Path ..) '.cache/playwright')
npx playwright install chromium
cd ..
pwsh -NoProfile -File scripts/verify-stage3.ps1
# 包含制度与版本管理的完整验收：
pwsh -NoProfile -File scripts/verify-stage4.ps1
```

脚本执行后端测试、真实 HTTP 回归及 Playwright 浏览器测试。账号与密码通过临时进程环境传递，不保存至文档、截图或 trace。登录、三类角色与权限使用真实后端；加载、空结果、500 和断网等异常通过浏览器拦截受控复现。

阶段 5 的制度管理页提供“开始解析”“重试解析”和“解析详情”，异步轮询任务并展示页码/段落、正文、提取方式、OCR 置信度与核对提示。解析完成显示“已解析，待索引”。启用方法见 [服务说明](../ai-service/README.md)，完整验证执行 `pwsh -NoProfile -File scripts/verify-stage5.ps1`。

阶段 6 提供“索引管理”，支持真实索引、状态刷新、失败重试和重建；索引完成后允许人工发布。索引请求单独设置 195 秒超时，普通 API 超时仍为 15 秒。“制度原文检索”仅展示当前授权的企业制度原文、版本、文本块和页码/段落，可下载来源文件。加载、空结果、服务失败和再次查询均有对应反馈。完整验证执行 `pwsh -NoProfile -File scripts/verify-stage6.ps1`。

阶段 7 提供“制度问答”：纯文本回答、真实引用与原文、来源文件下载、本人历史和游标加载更多。旧来源明确标识，权限撤回后重新打开历史隐藏答案；依据不足、冲突和模型失败有独立反馈，可重新提交。提交问题超时单独设置 320 秒，覆盖检索与模型总耗时。问答默认关闭，后端配置见 [阶段七说明](../docs/阶段7接口与权限说明.md)。`pwsh -NoProfile -File scripts/verify-stage7.ps1` 使用真实解析/检索和受控生成端验收页面，实际 LLM 质量仍待验证。

## 阶段八差旅申请

“我的申请”已接真实差旅接口：本人草稿与分页、表单保存、附件增删/下载、规则与带引用预审、旧分析失效、当前内容提交。输入未保存时先保存；来源撤权或过时要求重新预审；提交后只读。预审不读取附件正文。完整本地验收见 `scripts/verify-stage8.ps1`，使用隔离数据库和受控生成服务，不替代实际模型质量验收。

## 阶段九人工审批

ADMIN 的“审批流程配置”支持金额区间、有序节点、当前合格审批人选择、发布新版本和复制历史配置。“审批中心”显示本人待办/已处理、固定提交材料、授权预审与附件下载，填写意见后同意、拒绝或退回。员工可查看真实状态与历史、撤回以及重新编辑/预审/提交。所有操作由后端再次校验，不以菜单或按钮代替权限。验收执行 `pwsh -NoProfile -File scripts/verify-stage9.ps1`，生成端关闭，预审明确 UNKNOWN，人工决定结果。

## 阶段十任务监控

管理员菜单新增文档处理任务，查看最近任务与缓存计数，刷新处理状态并重新排队允许重试的失败任务。法务/员工不可访问此管理页；制度索引窗口对 PROCESSING 自动轮询。工程验收：pwsh -NoProfile -File scripts/verify-stage10.ps1。真实中间件尚待联调。
