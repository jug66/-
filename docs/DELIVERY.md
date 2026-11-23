# 笔试代码提交与启动说明

本文给接收方一份可以直接执行的源码交付说明，也适合作为邮件附件中的 `START_HERE.md`。项目是“回声 ECHO”无限层级留言板，后端使用 Java 21 + Spring Boot + PostgreSQL，前端使用 Vue 3 + Vite。

## 一、怎样发邮件

可以把源码压缩包作为邮件附件发送，但不要把当前工作目录原样压缩。`node_modules`、`backend/target`、`frontend/dist`、`.runtime` 和 `.tools` 都是本机生成物，体积大或包含本地运行数据；`.env` 可能包含密码，绝对不能发送。

建议使用项目脚本生成只包含源码、迁移、配置示例、文档和跨平台启动脚本的 ZIP：

```powershell
Set-Location D:\practice
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-source.ps1 -Name '你的姓名'
```

Linux/macOS：

```bash
cd /path/to/project
chmod +x scripts/*.sh
./scripts/package-source.sh '你的姓名'
```

生成文件在 `output/雪哈游-全栈-笔试题2-你的姓名-源码.zip`。如果公司邮箱限制附件大小，可以把同一个 ZIP 上传到公司允许的文件服务，再把访问链接和 SHA-256 校验值写进邮件；不要使用个人网盘的公开链接保存数据库密码或生产密钥。

当前工作环境没有登录你的邮箱，也没有配置 SMTP/API，因此我不能替你实际点击发送。你可以在自己的邮箱客户端中新建邮件，填写：

- 收件人：`it@naci-cloud.com`
- 标题：`你的姓名+雪哈游-全栈-笔试题2`
- 附件：`output/雪哈游-全栈-笔试题2-你的姓名-源码.zip`

正文模板：

```text
您好：

感谢您提供笔试机会。附件是我的“雪哈游-全栈-笔试题2”项目源码及启动说明。

项目技术栈：Java 21 / Spring Boot / PostgreSQL / Vue 3 / Vite。
默认开发模式会启动本机临时 PostgreSQL（127.0.0.1:55432）和 API（127.0.0.1:8080），前端访问地址为 http://127.0.0.1:5173。完整的 Windows、Linux、macOS 启动步骤和外部 PostgreSQL 配置见附件中的 docs/DELIVERY.md。

如需使用外部数据库，请先按文档配置 DATABASE_URL、DATABASE_USER、DATABASE_PASSWORD 和 CURSOR_SECRET，再运行后端启动脚本；Flyway 会在首次启动时自动执行数据库迁移。

祝好
你的姓名
```

请在 **10 月 25 日 24:00 前**完成发送；中文“24:00”通常表示 10 月 26 日 00:00，但应以对方通知中的时区和邮件系统截止规则为准。发送后保留“已发送”记录和附件名，必要时同时发送 ZIP 的 SHA-256 值。

## 二、所有系统的前置环境

| 软件 | 要求 | 用途 |
|---|---|---|
| JDK | 21（不是 JRE，也不是 Java 17） | 编译和运行后端 |
| Maven | 3.6.3 或更高 | 下载依赖、执行迁移和构建 JAR |
| Node.js | 20.19+ 或 22.12+，推荐 22 LTS | 运行 Vite 和前端构建 |
| npm | 随 Node.js 安装 | 安装锁定的前端依赖 |
| PostgreSQL | 默认开发模式不需要单独安装 | 外部数据库或生产部署时使用 17.11 |
| Docker | 可选 | 用 `compose.yml` 启动 PostgreSQL |

第一次启动需要联网下载 Maven/npm 依赖和本地 PostgreSQL 辅助程序。源码包没有携带这些依赖，避免平台和架构不匹配。

检查版本：

```text
java -version
mvn -version
node --version
npm --version
```

`JAVA_HOME` 必须指向 JDK 21 的安装目录。Linux/macOS 还需要 Bash；Windows 使用 PowerShell 5.1 或 PowerShell 7。

## 三、Windows 启动（默认本地开发模式）

在第一个 PowerShell 窗口：

```powershell
Set-Location D:\practice
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

如果 JDK 21 安装目录不同，只修改 `JAVA_HOME`。脚本会在后端目录执行 Maven，启动本地 PostgreSQL `127.0.0.1:55432`，再启动 API `127.0.0.1:8080`。看到 Spring Boot 启动完成后保持窗口运行。

在第二个 PowerShell 窗口：

```powershell
Set-Location D:\practice
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-frontend.ps1
```

浏览器访问 <http://127.0.0.1:5173>。前端开发服务器会把 `/api` 代理到后端。停止时，前端窗口按 `Ctrl+C`；后端可以按 `Ctrl+C`，或另开窗口执行：

```powershell
Set-Location D:\practice
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-backend.ps1
```

开发数据库和上传文件分别保存在 `.runtime/postgres` 与 `.runtime/uploads`，重启会保留。不要同时启动两个后端实例；如果 8080、5173 或 55432 被占用，先停止旧实例。

## 四、Linux/macOS 启动（默认本地开发模式）

解压后在项目根目录执行：

```bash
cd /path/to/project
chmod +x scripts/*.sh
export JAVA_HOME=/path/to/jdk-21
./scripts/start-backend.sh
```

再开一个终端：

```bash
cd /path/to/project
./scripts/start-frontend.sh
```

浏览器访问 <http://127.0.0.1:5173>。停止后端可以按 `Ctrl+C`，或执行 `./scripts/stop-backend.sh`。脚本使用项目根目录的 `.runtime` 保存开发数据库；如果当前 CPU 架构不在嵌入式 PostgreSQL 依赖支持范围内，请改用下面的外部 PostgreSQL 方式。

## 五、外部 PostgreSQL / Docker 方式

默认开发模式适合单机演示。需要手工管理 PostgreSQL 或在服务器运行时，先创建数据库和用户，或者在项目根目录执行：

```text
copy .env.example .env       # Windows
cp .env.example .env         # Linux/macOS
docker compose up -d postgres
```

编辑 `.env` 中的 `DATABASE_PASSWORD`。注意：Docker Compose 会读取 `.env`，Spring Boot **不会自动读取 `.env`**，启动 API 前仍要把变量设置到当前终端环境：

Windows PowerShell：

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/message_board'
$env:DATABASE_USER = 'message_board'
$env:DATABASE_PASSWORD = '替换成数据库密码'
$env:CURSOR_SECRET = '至少32字节的随机字符串'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1 -ExternalDatabase
```

Linux/macOS：

```bash
export DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/message_board'
export DATABASE_USER='message_board'
export DATABASE_PASSWORD='替换成数据库密码'
export CURSOR_SECRET="$(openssl rand -base64 48)"
./scripts/start-backend.sh --external
```

外部模式会先构建 `backend/target/message-board-1.0.0.jar`，再连接上述 PostgreSQL。Flyway 会自动执行 `backend/src/main/resources/db/migration` 下的版本化迁移，不要手工按顺序复制 SQL。外部数据库模式仍需启动前端：`./scripts/start-frontend.sh` 或 Windows 的 `start-frontend.ps1`。

## 六、生产环境必须配置的变量

| 变量 | 本地默认值 | 生产要求 |
|---|---|---|
| `DATABASE_URL` | 本地嵌入式数据库 | 使用稳定 PostgreSQL 地址 |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `postgres` / 空 | 独立、最小权限账号；密码不要提交到 ZIP |
| `CURSOR_SECRET` | 开发可临时生成 | 稳定随机值，至少 32 字节；更换会使已有游标失效 |
| `COOKIE_SECURE` | `false` | HTTPS 部署必须为 `true` |
| `PUBLIC_URL` | `http://127.0.0.1:5173` | 设置为真实 HTTPS 前端地址 |
| `UPLOAD_DIR` | `../.runtime/uploads` | 使用绝对路径，并确保应用用户可写；备份时一并备份 |
| `PORT` | `8080` | 按反向代理或平台端口设置 |
| `ADMIN_USER_IDS` | 空 | 填已有用户 UUID，多个用逗号分隔 |
| `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` | 空 | 需要邮箱验证或找回密码时配置 |
| `MAIL_FROM` | 空 | 设置为已验证的发件地址 |
| `UPLOAD_USER_DAILY_BYTES` | `104857600` | 按磁盘容量和业务量调整 |
| `UPLOAD_PENDING_BYTES` | `52428800` | 按未发布附件占用量调整 |

生产建议使用 `SPRING_PROFILES_ACTIVE=prod`。该配置会检查稳定的游标密钥、HTTPS Cookie、HTTPS 公共地址和绝对上传目录；不要把本地开发服务器直接暴露到公网，还需要 HTTPS、数据库备份和恢复演练。

## 七、项目启动后验证

1. 打开 `http://127.0.0.1:5173`，注册并登录。
2. 创建文字留言，继续回复几层，刷新后检查父子关系和分页。
3. 上传 JPG/PNG/WebP 图片，确认留言卡片能显示；单条最多 3 张、单张不超过 5 MB。
4. 在 15 分钟内编辑自己的留言，检查版本冲突和编辑历史；删除留言后确认子回复仍保留结构占位。
5. 测试通过后再打包：Windows 运行 `scripts/test.ps1`；Linux/macOS 可在后端执行 `mvn test`，再在前端执行 `npm ci`、`npm run build` 和 `npm test`。

## 八、常见问题

- **找不到 Java 或版本不是 21**：重新设置 `JAVA_HOME`，确认 `java -version` 输出 21；不要只安装 JRE。
- **Maven 下载失败**：检查代理、网络和 Maven 镜像；不要把本机 `target` 目录复制到别人的电脑代替构建。
- **前端依赖安装失败**：删除不完整的 `frontend/node_modules` 后重新执行 `npm ci`；Windows 遇到文件占用时先停止 Vite。
- **端口已占用**：确认旧的 Java/Node 进程已停止，或调整端口并同步修改前端代理和 `PUBLIC_URL`。
- **外部数据库连接失败**：检查 PostgreSQL 是否监听目标地址、用户是否有数据库权限，以及四个 `DATABASE_*` 变量是否在同一个终端生效。
- **上传失败**：检查 `UPLOAD_DIR` 存在且应用用户可写；生产使用绝对路径，不要使用临时目录。
- **收不到验证/找回邮件**：检查 SMTP 变量、发件地址和邮件服务商的授权密码；未配置 SMTP 时本地不会把重置令牌返回给浏览器。
