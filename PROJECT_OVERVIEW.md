# 回声 ECHO 留言板｜项目总览

## 1. 项目定位

回声 ECHO 是一个支持无限层级回复的留言板。访客可以浏览公开内容，注册用户可以发布、回复、编辑、删除留言并上传图片。系统还包含搜索、最近回复、通知、举报和管理员治理功能。

“无限层级”表示业务和数据库不限制回复深度；页面按分支分页和深层聚焦加载，不会一次性把整棵留言树全部返回。

## 2. 技术栈

| 层次 | 技术 | 作用 |
|---|---|---|
| 前端 | Vue 3、TypeScript、Vite | 页面、交互、开发服务器和生产构建 |
| 后端 | Java 21、Spring Boot 3.5 | HTTP API、事务、权限和业务规则 |
| 数据访问 | MyBatis、HikariCP | 参数化 SQL 和数据库连接池 |
| 数据库 | PostgreSQL 17.11 | 用户、留言树、附件元数据、通知和治理数据 |
| 迁移 | Flyway | 启动时自动执行版本化 SQL 迁移 |
| 安全 | Spring Security、Session、CSRF、Argon2id | 登录、授权、请求防护和密码散列 |
| 图片 | 本地文件 + PostgreSQL 元数据 | JPG、PNG、WebP 上传与生命周期管理 |
| 邮件 | Spring Mail / SMTP（可选） | 邮箱验证和密码找回 |
| 测试 | JUnit 5、Spring Boot Test、Vitest | 后端集成测试和前端单元测试 |

项目按单体单实例运行，不依赖 Redis、消息队列、Elasticsearch 或微服务。Session、限流状态和游标密钥状态默认保存在应用进程内。

## 3. 功能范围

### 用户功能

- 注册、登录、注销和当前用户查询。
- 密码使用 Argon2id 散列；可选配置邮箱验证和密码找回。
- 访客可以阅读，登录用户才能发布、回复和上传图片。

### 留言功能

- 根留言按时间排序并分页。
- 任意留言都可以继续回复，使用 `parent_id` 保存直接父节点，使用 `root_id` 标识讨论根。
- 正文为纯文本，长度为 1～2000 字符。
- 作者可以在发布后 15 分钟内编辑自己的留言；使用 revision 做并发冲突检查，并保留编辑历史。
- 删除采用软删除：清空正文但保留节点和子回复，页面显示占位内容。
- 图片最多 3 张，每张不超过 5 MB，仅支持 JPG、PNG、WebP；WebP 会清理元数据并转为 PNG。

### 浏览和治理功能

- 回复分支独立分页，支持祖先面包屑、深层聚焦和可分享的 `?focus=<id>` 地址。
- 支持公开文字搜索、最近回复和线程统计。
- 回复他人留言时生成通知，通知可以分页和标记已读。
- 用户可以举报；管理员可以隐藏/恢复留言、锁定/归档线程、封禁用户、处理举报并查看审计记录。

## 4. 前后端如何协作

```text
浏览器 Vue 页面
    ↓ /api JSON 请求
Spring Security（Session、CSRF、限流）
    ↓
Controller（输入输出）
    ↓
Service（事务、权限、状态机、幂等）
    ↓
MyBatis Mapper（参数化 SQL）
    ↓
PostgreSQL + 本地上传目录 + 可选 SMTP
```

发布留言时，前端先校验正文和附件并生成 requestId；后端验证文件头、图片格式、尺寸和用户额度，在事务中锁定父节点，检查线程状态和权限，写入留言、附件元数据、幂等记录，并在需要时创建通知。

## 5. 无限层级的实现方式

数据库使用邻接表保存树：

- `parent_id`：直接父留言；根留言为空。
- `root_id`：讨论根 ID，便于按线程查询。
- `created_at + id`：稳定排序键，避免相同时间下顺序不确定。
- `comment_children_time(parent_id, created_at, id)`：支持直接子回复分页。

前端使用迭代方式生成可见留言行：初始只请求根留言；展开某个节点时只请求它的直接子节点；每个分支单独保存 cursor 和加载状态。页面最多展示有限数量的卡片，并保护当前聚焦分支，避免深层留言拖垮浏览器。

## 6. 主要代码位置

```text
backend/src/main/java/dev/messageboard/
  auth/          注册、登录、Session、密码找回和限流
  comment/       留言树、游标、图片、编辑、搜索、统计和治理
  notification/  回复通知、分页、去重和已读
  common/        参数检查、统一错误、请求保护和生产配置
backend/src/main/resources/
  application.yml
  db/migration/  Flyway V1～V8 迁移
frontend/src/
  App.vue        页面状态和主要交互
  api.ts         API、CSRF、会话过期和类型
  tree.ts        树遍历、分支分页和幂等提交
  ui.ts          展示规则
  style.css      桌面、移动端和可访问性样式
scripts/         启动、停止、测试和源码打包入口
```

## 7. 数据库核心表

- `board_user`：用户账号、安全状态和邮箱。
- `comment`：留言树、正文、删除/隐藏状态、线程状态和编辑版本。
- `creation_request`：发布幂等请求，避免网络重试重复创建。
- `comment_attachment`：图片归属、顺序、格式、尺寸和清理状态。
- `account_token`：邮箱验证和密码找回 token。
- `comment_report`、`moderation_audit`：举报、管理员治理和审计。
- `upload_usage`：用户每日上传额度。
- `comment_revision`：编辑历史。
- `notification`：回复通知、去重和已读状态。

## 8. 已实现的保护措施

- 参数化 SQL、Session、CSRF、权限校验和统一错误响应。
- 正文纯文本渲染，不执行 HTML、脚本或 Markdown。
- 上传检查文件头、解码结果、格式、大小、数量和用户额度。
- 发布幂等键、数据库事务锁和 revision 乐观并发检查。
- 游标使用服务端 HMAC 签名，客户端不能伪造其他分支的分页游标。
- 生产 profile 要求稳定 `CURSOR_SECRET`、HTTPS Cookie、HTTPS `PUBLIC_URL` 和绝对上传目录。

## 9. 当前边界

当前版本按单实例和本地文件存储设计；没有实时 WebSocket/SSE 推送。公网部署还需要 HTTPS、数据库备份恢复演练、对象存储或病毒扫描、共享 Session/限流，以及多实例下的异步通知方案。

## 10. 验证情况

最近一次验证中，后端测试通过，前端单元测试、TypeScript 检查和 Vite 构建通过；浏览器深层回归连续展开 85 层，没有横向溢出和页面错误。具体启动和验收步骤请阅读同一目录下的 `START_HERE.md`。
