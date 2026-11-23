# 雪哈游-全栈-笔试题2｜项目提交与运行说明

> 请先阅读本文，再运行项目。本文适用于 Windows、Linux 和 macOS。

## 1. 项目是什么

这是一个支持无限层级回复的留言板：用户可以注册、登录、发布留言、回复、删除和编辑自己的留言，也可以上传图片。后端是 Java 21 + Spring Boot，数据库是 PostgreSQL，前端是 Vue 3 + Vite。

项目目录中同时提供了：

```text
backend/       Spring Boot API、Flyway 数据库迁移和后端测试
frontend/      Vue 3 前端、Vite 配置和前端测试
scripts/       Windows 与 Linux/macOS 的启动、停止、测试、打包脚本
docs/          技术设计、需求、验证报告和详细交付说明
compose.yml    可选的 PostgreSQL Docker 配置
.env.example   环境变量示例，不包含真实密码
```

## 2. 解压后的第一步

请把 ZIP 解压到一个完整目录，不要只复制其中的 `backend` 或 `frontend` 文件夹。进入同时包含 `backend`、`frontend` 和 `scripts` 的项目根目录。

第一次启动需要联网下载 Maven/npm 依赖和本地 PostgreSQL 辅助程序。压缩包没有携带 `node_modules`、`target`、`dist` 或本地数据库，这些内容会由当前电脑重新生成。

## 3. 安装环境

| 软件 | 版本 |
|---|---|
| JDK | 21，必须是 JDK，不是 JRE |
| Maven | 3.6.3 或更高 |
| Node.js | 20.19+ 或 22.12+ |
| npm | 随 Node.js 安装 |
| PostgreSQL | 默认开发模式不需要；外部模式使用 PostgreSQL 17.11 |
| Docker | 可选，只用于启动外部 PostgreSQL |

检查是否安装正确：

```text
java -version
mvn -version
node --version
npm --version
```

如果 `java -version` 不是 21，请设置 `JAVA_HOME` 指向 JDK 21 的安装目录。

## 4. 推荐启动方式：本地开发数据库

这种方式最适合评审和本机演示，不要求额外安装 PostgreSQL。后端会启动一个只监听本机的临时 PostgreSQL，地址是 `127.0.0.1:55432`；API 使用 `127.0.0.1:8080`。

### Windows

第一个 PowerShell 窗口：

```powershell
Set-Location '解压后的项目根目录'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1
```

第二个 PowerShell 窗口：

```powershell
Set-Location '解压后的项目根目录'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-frontend.ps1
```

### Linux/macOS

第一个终端：

```bash
cd /path/to/unpacked-project
chmod +x scripts/*.sh
export JAVA_HOME=/path/to/jdk-21
./scripts/start-backend.sh
```

第二个终端：

```bash
cd /path/to/unpacked-project
./scripts/start-frontend.sh
```

### 打开网页

浏览器访问：<http://127.0.0.1:5173>

前端启动后会自动把 `/api` 请求代理到后端。看到后端启动完成、前端显示 Vite 地址后即可使用。

### 停止项目

前端窗口按 `Ctrl+C`。后端也可以按 `Ctrl+C`，或者执行：

```powershell
# Windows
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-backend.ps1
```

```bash
# Linux/macOS
./scripts/stop-backend.sh
```

本地数据会保存在项目根目录的 `.runtime` 中，重启后仍会保留。不要同时启动两个后端实例。

## 5. 外部 PostgreSQL / Docker 启动方式

如果不使用本地开发数据库，请先准备 PostgreSQL，或者在项目根目录执行：

```text
copy .env.example .env       # Windows
cp .env.example .env         # Linux/macOS
docker compose up -d postgres
```

修改 `.env` 中的 `DATABASE_PASSWORD`。然后把变量设置到当前终端；Spring Boot 不会自动读取 `.env`。

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

外部模式会构建并运行 `backend/target/message-board-1.0.0.jar`。Flyway 会在后端启动时自动执行数据库迁移，不需要手工执行 SQL。前端仍按上面的方式启动。

## 6. 常用配置

| 环境变量 | 说明 |
|---|---|
| `DATABASE_URL` | PostgreSQL JDBC 地址 |
| `DATABASE_USER` / `DATABASE_PASSWORD` | 数据库账号和密码 |
| `CURSOR_SECRET` | 游标签名密钥；生产环境使用稳定的随机值，至少 32 字节 |
| `PORT` | API 端口，默认 8080 |
| `PUBLIC_URL` | 前端访问地址，本地默认为 `http://127.0.0.1:5173` |
| `COOKIE_SECURE` | 本地 HTTP 使用 `false`；HTTPS 部署使用 `true` |
| `UPLOAD_DIR` | 图片上传目录；生产环境使用绝对路径 |
| `ADMIN_USER_IDS` | 管理员用户 UUID，多个用逗号分隔 |
| `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` | 邮箱验证和找回密码所需的 SMTP 配置 |
| `MAIL_FROM` | 邮件发件地址 |

不要把真实密码、SMTP 凭据或生产密钥写进源码包，也不要提交 `.env`。

## 7. 启动后快速验收

1. 打开 `http://127.0.0.1:5173`。
2. 注册并登录一个账号。
3. 发布一条文字留言，再连续回复几层，刷新页面检查父子关系。
4. 上传 JPG、PNG 或 WebP 图片，确认留言卡片能显示。
5. 编辑自己的留言，检查编辑窗口和编辑历史。
6. 删除留言，确认子回复仍保留结构占位。

限制：一条留言最多 3 张图片，每张不超过 5 MB；支持 JPG、PNG、WebP。

## 8. 测试与构建

Windows：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test.ps1
```

Linux/macOS：

```bash
cd backend && mvn test
cd ../frontend && npm ci && npm run build && npm test
```

## 9. 邮件提交信息

收件人：`it@naci-cloud.com`

标题格式：

```text
你的姓名+雪哈游-全栈-笔试题2
```

附件建议使用源码 ZIP，例如：

```text
雪哈游-全栈-笔试题2-你的姓名-源码.zip
```

邮件正文：

```text
您好：

感谢您提供笔试机会。附件是我的“雪哈游-全栈-笔试题2”项目源码及启动说明。
项目使用 Java 21、Spring Boot、PostgreSQL、Vue 3 和 Vite。
解压后请先阅读根目录 START_HERE.md；默认开发模式不需要单独安装 PostgreSQL。

祝好
你的姓名
```

请在 **10 月 25 日 24:00 前**发送。当前环境无法代替你登录邮箱发送邮件，因此需要你在自己的邮箱客户端中添加 ZIP 附件并点击发送。

## 10. 项目总览

项目涉及的功能、技术栈、前后端协作、无限层级实现、数据库表、主要代码位置和当前边界，请阅读同目录下的 `PROJECT_OVERVIEW.md`。
