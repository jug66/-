# 技术设计与三阶段增强

面向第一次接手项目的页面、功能和数据库总览见 [项目设计与实现汇报](project-summary.md)；本文保留更偏实现细节的 API、数据流和性能设计。

需求依据：`requirements.md`。V1-V5 完成基础留言、图片和治理基础；V6-V8 增加编辑历史、通知、搜索和线程统计；前端同步提供深层导航和管理工作台。

## 数据流

浏览器 Vue → 同源 `/api` → Spring Security（会话、CSRF、限流）→ Controller → 事务 Service → MyBatis → PostgreSQL。

开发时 Vite 将 `/api` 代理到 8080；正式部署将前端静态资源和 API 置于同一 HTTPS 域名。采用模块化单体，无 Redis、消息队列或搜索引擎。会话和限流在进程内，重启需重新登录，多实例部署前需改为共享会话和共享限流。

## 关键决策

- Java 21，Spring Boot 3.5.16，MyBatis starter 3.0.5。版本兼容性依据 [MyBatis 官方矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。
- PostgreSQL 邻接表：`parent_id` 表示直接父节点，`root_id` 表示讨论根。UUID 作为身份；父子关系创建后不修改，因此业务上没有深度限制。
- 根列表和每个父节点的直接孩子分别分页。采用 `(created_at,id)` 游标，不使用大 OFFSET，不递归加载整棵树。作者 JOIN、子节点探测在一次有界 SQL 中完成。子节点探测使用与 `(parent_id,created_at,id)` 索引匹配的 `ORDER BY ... LIMIT 1` 标量子查询；PostgreSQL 17 的普通 EXISTS 可能生成全表哈希子计划，无排序的 LIMIT 也可能在极端热点分布下选用顺序扫描，因此用实际执行计划回归验证。
- 游标包含父节点、排序方向、时间和 ID，并使用服务端 HMAC 签名。签名密钥需由正式部署环境配置；本地每次启动生成随机密钥，旧游标失效后刷新。
- 删除在事务中锁定自身行，清空内容、写删除时间，保留所有节点与后代。发布回复锁定直接父节点，与删除使用相同行锁。
- 创建幂等：用户 ID + 请求 UUID，事务级 advisory lock 串行同一键；先查幂等记录，再验证父节点；保存摘要和结果 ID，每次重放读取结果当前状态。记录至少保存 24 小时（首版不自动清理）。
- 密码 9～128 个字符，使用 Argon2id 并拒绝内置常见密码；服务端 Session + CSRF，空闲 30 分钟、绝对 7 天。登录轮换会话 ID；不使用浏览器 localStorage 保存令牌。可选邮箱用于验证和密码找回。
- API 的所有 ID 使用字符串。正文为纯文本，前端插值显示；服务器控制作者、根节点、时间和状态，拒绝未知 JSON 字段。
- 图片采用“两阶段提交”：登录用户先以批次编号上传最多 3 张图片，服务端校验文件头、解码和尺寸后将文件写入 `board.upload-dir`，数据库保存归属、顺序、格式、尺寸和生命周期；创建留言事务再把待上传记录绑定到留言。WebP 输入会重新编码为 PNG，避免直接信任文件扩展名和元数据。
- 前端以迭代算法生成可见行，限制展示 200 条、缓存 1000 条；超过预算采用聚焦分支和按需重载，避免递归调用栈。折叠、焦点返回及每个分支的分页状态独立。

## 三阶段增量

- 阶段一：`App.vue` 和 `style.css` 提供统一的留言操作层级、附件网格、线程状态、编辑态、通知/搜索/管理面板和移动端布局。
- 阶段二：`/context` 增加祖先继续游标，新增 `/comments/recent-replies`，聚焦地址使用 `?focus=<id>`，前端按需继续加载祖先。
- 阶段三：Flyway V6 增加 `comment_revision` 和编辑字段；V7 增加 `notification`；V8 增加 `pg_trgm` 可见正文索引。搜索、通知、统计和治理页面均复用现有权限与软删除规则。

## API

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/auth/csrf` | 获取 CSRF token |
| POST | `/api/auth/register` | 注册，成功后手动登录 |
| POST | `/api/auth/login` | 登录并轮换 session |
| POST | `/api/auth/logout` | 注销当前会话 |
| GET | `/api/auth/me` | 当前用户，访客返回 null |
| GET | `/api/auth/verify-email?token=...` | 验证可选邮箱 |
| POST | `/api/auth/forgot-password` | 请求密码找回，固定返回提示 |
| POST | `/api/auth/reset-password` | 使用一次性 token 重置密码 |
| GET | `/api/comments?sort=newest&limit=20&cursor=...` | 根分页，sort 可为 oldest |
| GET | `/api/comments/{id}` | 单条最新状态 |
| GET | `/api/comments/{id}/context?ancestorLimit=8&limit=20` | 深层聚焦所需的目标留言、有限祖先路径和首屏回复 |
| GET | `/api/comments/recent-replies?limit=20&cursor=...` | 最近回复列表 |
| GET | `/api/comments/search?q=...&limit=20&cursor=...` | 可见文字搜索 |
| GET | `/api/comments/{id}/stats` | 当前线程后代数量和最近活动 |
| GET | `/api/comments/{id}/replies?limit=20&cursor=...` | 直接子回复，固定 oldest |
| POST | `/api/comments` | `{content,parentId?,requestId}` |
| PUT | `/api/comments/{id}` | 作者在编辑窗口内修改文字，要求 `{content,revision}` |
| GET | `/api/comments/{id}/revisions` | 作者查看自己的编辑历史 |
| DELETE | `/api/comments/{id}` | 作者删除并保留结构 |
| GET | `/api/notifications?limit=20&cursor=...` | 当前用户通知 |
| POST | `/api/notifications/{id}/read` | 标记通知已读 |
| POST | `/api/comments/{id}/reports` | 登录用户举报留言 |
| GET | `/api/moderation/reports` | 管理员查看未处理举报 |
| GET | `/api/moderation/me` | 查询当前会话是否为管理员 |
| GET | `/api/moderation/audit` | 管理员查看治理审计 |
| POST | `/api/moderation/comments/{id}` | 管理员隐藏或恢复留言 |
| POST | `/api/moderation/threads/{id}` | 管理员设置 open/resolved/locked/archived |
| POST | `/api/moderation/users/{id}` | 管理员封禁或解封用户 |
| POST | `/api/moderation/reports/{id}` | 管理员处理举报 |
| POST | `/api/uploads/images?batchId=...` | 登录后上传最多 3 张 JPG/PNG/WebP 图片，返回附件 ID 和预览地址 |
| DELETE | `/api/uploads/images/{id}` | 删除当前用户尚未绑定留言的临时图片 |
| GET | `/api/attachments/{id}` | 读取已绑定到有效留言的图片，或读取当前用户自己的临时图片 |
| GET | `/api/health` | 存活信息，无敏感配置 |

错误格式为 `{message,requestId}`。400 参数，401 登录，403 权限/CSRF，404 不存在，409 状态/幂等冲突，413 超限，429 限流，503 数据库暂时不可用。

## 运行边界与验证

标准运行连接外部 PostgreSQL 17.11，Flyway 自动迁移。本地辅助入口使用独立 PostgreSQL 17.11 真实进程、数据位于 `.runtime`，只监听 loopback；测试使用隔离的临时 PostgreSQL，不接触开发数据。辅助模式只适用于本地开发。

普通写请求体上限 32 KiB；图片上传单张 5 MiB、单批最多 3 张、multipart 总请求 16 MiB，并按用户日额度和待发布容量预算。限流使用进程内固定窗口：每 IP 每分钟读取 600、认证 30、写入 60；已登录写入再按用户限流。默认不信任转发 IP 请求头。生产 profile 强制固定游标密钥、HTTPS Cookie、HTTPS 公共地址和绝对上传目录。

测试按数据库约束、API 权限/会话、分页、幂等、并发删除、规模与深链、前端状态、浏览器流程分层执行。实际结果单独写入测试报告，不将设计目标当成实测。
