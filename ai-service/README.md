# 内部解析与向量检索服务（阶段 5/6）

FastAPI 内部服务，使用 PyMuPDF 提取电子 PDF；无文本扫描页、PNG/JPEG 使用 PaddleOCR 的 PP-OCRv5 mobile 中文模型；DOCX 解析 OOXML 正文及表格段落。阶段六新增独立的 Embedding 与向量存储进程。Python 不访问业务数据库，不接受文件 URL；切片、权限、版本和发布由 Java 负责。

## 安装与启动

本次验证环境：Windows、Python 3.13.3、CPU。直接依赖固定在 requirements.txt，完整验收环境含传递依赖见 requirements-lock.txt；requirements-dev.txt 包含 pytest/httpx。可在项目根目录安装：

```powershell
python -m venv .cache/ai-venv
python -m pip --python .cache/ai-venv/Scripts/python.exe install -r ai-service/requirements-lock.txt
```

若本机 venv 的 ensurepip 不可用，上面的外部 pip --python 安装方式仍可使用。不要往全局 Python 安装本项目依赖。

Java 和 Python 需配置**同一绝对** DOCUMENT_STORAGE_ROOT（例如项目下 data/documents 的绝对路径），并创建此目录；PARSER_SECRET 使用同一份至少 32 字符的随机内部密钥。Java 额外设置 PARSER_ENABLED=true、PARSER_URL=http://127.0.0.1:8090。环境变量模板见根目录 .env.example，应用不会自动加载该文件。

```powershell
# 在项目根目录；先自行配置共享目录和随机 PARSER_SECRET。
$env:PADDLE_PDX_CACHE_HOME = Join-Path (Get-Location) '.cache/paddle'
$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = 'True'
$env:PYTHONUTF8 = '1'
Set-Location ai-service
../.cache/ai-venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8090 --workers 1 --no-access-log
```

首次 OCR 会下载官方模型，建议在内部服务开放前准备好模型。电子文档不初始化 Paddle。两个模型使用项目缓存；本机 Paddle 3.3.1 的 oneDNN 路径出现兼容异常，代码显式关闭 MKL-DNN，已验证 CPU 识别。

## 运行边界

- 默认最多 100 页 PDF、10 MiB 文件、4000 万像素；DOCX 解压最多 30 MiB/1000 项、最多 2000 个非空段落。
- 每个来源最多 10 万字符，总计最多 200 万字符。空文档失败，空页保留警告。
- PARSER_CONCURRENCY 默认 1，每个请求独立子进程；PARSER_TIMEOUT_SECONDS 默认 180，超时终止进程并释放容量。单实例使用一个 Uvicorn worker，避免把进程内并发限制误当集群限制。
- Java 默认客户端超时 190 秒，任务租约为客户端超时 + 30 秒。Python 超时必须小于 Java 超时；超过租约的任务标记失败，需手动重试。
- OCR 平均置信度低于 0.85 提示人工核对；置信度并不保证文字正确。DOCX 引用段落，不推测页码。页序用于来源追溯，不等于完成了复杂版面重建。
- 仅内部调用，使用 X-Parser-Secret；默认绑定回环地址，正式部署应限制网络访问，跨主机配置内部 TLS。Python 只读共享文件，结果由 Java 校验身份、摘要、来源顺序后原子保存。

## 验证与资料

### 阶段六本地向量服务

使用 Qdrant Client 1.19.1 的本地持久化模式、FastEmbed 0.8.1、ONNX Runtime 1.31.0，模型为 `BAAI/bge-small-zh-v1.5`，512 维、Cosine。模型来源 `Qdrant/bge-small-zh-v1.5` 固定为快照 `46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59`，启动只读取本地快照，首次下载：

```powershell
# 项目根目录；先按上文安装完整 requirements-lock.txt。
.cache/ai-venv/Scripts/python.exe scripts/prepare-embedding.py
$env:EMBEDDING_CACHE = Join-Path (Get-Location) '.cache/embedding'
$env:VECTOR_STORAGE_ROOT = Join-Path (Get-Location) 'data/vectors'
# 先配置随机 RETRIEVAL_SECRET，Java/Python 使用同一份，至少 32 字符。
Set-Location ai-service
../.cache/ai-venv/Scripts/python.exe -m uvicorn app.retrieval:app --host 127.0.0.1 --port 8091 --workers 1 --no-access-log
```

Java 配置 `RETRIEVAL_ENABLED=true`、`RETRIEVAL_URL=http://127.0.0.1:8091` 和共享 `RETRIEVAL_SECRET`。模型准备阶段需要网络，正文和问题在运行时仅在本机推理。服务独立于 8090 解析进程，使用独立密钥。

本地 Qdrant 仅支持一个进程独占持久目录，勿启动多个 worker 共用目录。服务使用非阻塞互斥锁控制索引/查询并发，繁忙返回 429，Java 返回可重试 503；每次索引最多 10000 块，每块最多 400 字符。Java 为检索提供最多 10000 个实时授权键，先在向量库过滤再排序，返回后重新授权并从 MySQL 取正文。范围快照仅用于追溯，不是权限依据。

超时、进程中断或响应无效时版本保持 PARSED，索引任务可手动重试；不自动发布。Java 索引调用 180 秒，租约 210 秒。Python 线程中的 ONNX 调用没有解析服务的硬终止子进程；超时后运算可能继续，但旧代次不能成为有效索引。失效采用 MySQL 逻辑门禁，向量文件仍保留旧代次，物理垃圾回收与 Qdrant 服务端迁移后续完善。本地模式用于单机演示，超过 10000 个授权块会明确拒绝检索，不截断候选集。

参考：[FastEmbed 支持模型](https://qdrant.github.io/fastembed/examples/Supported_Models/)、[Qdrant 本地模式与 Embedding](https://qdrant.tech/documentation/tutorials-basics/generate-embeddings-fastembed/)。

完整阶段六验收：`pwsh -NoProfile -File scripts/verify-stage6.ps1`；[接口说明](../docs/阶段6接口与权限说明.md)、[验收记录](../docs/阶段6验收记录.md)、[学习笔记](../My_Learning/stage6_note.md)。

完整验证：从项目根目录执行 `pwsh -NoProfile -File scripts/verify-stage5.ps1`，需要本机 MySQL、JDK/Maven、Node、Chromium 和已安装的 Python 环境。脚本使用独立数据库及文件目录，自动生成电子/扫描 PDF、PNG、JPEG、DOCX 和损坏 PDF 样例，执行真实解析及界面验证。

单独 Python 检查：在 ai-service 执行 `../.cache/ai-venv/Scripts/python.exe -m pytest -q --basetemp=../.cache/pytest-manual`。该 basetemp 仅用于测试，pytest 会清理它，不要指向业务目录。

[接口契约](../docs/阶段5接口与权限说明.md)、[验收记录](../docs/阶段5验收记录.md)、[学习笔记](../My_Learning/stage5_note.md)。实现依照已安装的 PaddleOCR/PaddleX API 核对；参考 [PaddleOCR 文档](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/OCR.html)、[PyMuPDF 文档](https://pymupdf.readthedocs.io/en/latest/recipes-text.html)。生产分发时需评估各依赖与 OCR 模型许可，包含 PyMuPDF 的 AGPL/商业许可选项。
