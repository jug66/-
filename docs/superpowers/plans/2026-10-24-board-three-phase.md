# 留言板三阶段增强实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在当前无限层级留言板上完成视觉交互、深层导航和产品能力三阶段增强。

**Architecture:** 继续使用 Vue 单页、Spring Boot 模块化单体、MyBatis 和 PostgreSQL。阶段一只修改前端呈现；阶段二复用现有上下文和游标机制；阶段三使用 Flyway 增量迁移，并将编辑、通知、搜索、统计和治理工作台拆成独立的后端接口。

**Tech Stack:** Vue 3, TypeScript, Vite, Vitest, Java 21, Spring Boot 3.5, MyBatis, PostgreSQL, Flyway。

**Spec:** `docs/superpowers/specs/2026-10-24-board-three-phase-design.md`

## Global Constraints

- 留言正文仍是纯文本，图片独立存储并沿用现有 3 张/5MB/JPG-PNG-WebP 限制。
- 无限层级只限制单次加载量，不允许一次性递归加载整棵树。
- 所有隐藏、删除内容必须从公开留言、附件、搜索和通知中排除。
- 所有管理员动作继续由后端权限校验，前端不能替代授权。
- 每个行为变更先增加失败测试，再写最小实现。

---

### Task 1: 阶段一前端设计令牌和工具栏

**Files:**
- Modify: `frontend/src/style.css`
- Modify: `frontend/src/App.vue`
- Test: `frontend/src/api.test.ts` or add `frontend/src/ui.test.ts`

**Steps:**
- [x] 增加颜色、间距、触控尺寸和状态样式的失败断言。
- [x] 调整标题、讨论工具栏、操作按钮、提示条和响应式布局。
- [x] 将留言菜单动作从 ID 信息扩展为复制链接、举报和删除入口。
- [x] 运行前端测试和构建。

### Task 2: 阶段一附件和深层卡片交互

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/style.css`
- Test: `frontend/src/tree.test.ts`

**Steps:**
- [x] 为没有回复数量、已删除留言、图片预览和深度上限增加失败测试。
- [x] 实现附件网格、统一的“查看对话/继续此分支”文案和更清晰的分支控制。
- [x] 实现移动端线程摘要和可见的焦点路径。
- [x] 运行 Vitest、vue-tsc 和 Vite 构建。

### Task 3: 阶段二祖先继续加载

**Files:**
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentController.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentService.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentMapper.java`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.vue`
- Test: `backend/src/test/java/dev/messageboard/BoardIntegrationTest.java`
- Test: `frontend/src/tree.test.ts`

**Steps:**
- [x] 先写祖先游标和继续加载的失败集成测试。
- [x] 增加签名祖先游标，返回 `hasMoreAncestors` 和 `ancestorCursor`。
- [x] 在前端增加“加载更早祖先”并防止重复路径。
- [x] 运行后端集成测试和前端测试。

### Task 4: 阶段二永久链接和最近回复

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/style.css`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentController.java`
- Test: `frontend/src/api.test.ts`
- Test: `backend/src/test/java/dev/messageboard/BoardIntegrationTest.java`

**Steps:**
- [x] 先写 focus URL、复制链接和最近回复接口的失败测试。
- [x] 增加公开的最近回复分页接口，沿用权限和状态过滤。
- [x] 增加复制留言链接动作和最近回复入口。
- [x] 运行全套前后端测试。

### Task 5: 阶段三编辑和历史

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__comment_revisions.sql`
- Modify: `backend/src/main/java/dev/messageboard/comment/Comment.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentMapper.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentService.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentController.java`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.vue`
- Test: `backend/src/test/java/dev/messageboard/BoardIntegrationTest.java`

**Steps:**
- [x] 先写编辑权限、时间窗口、版本冲突和历史不可泄露的失败测试。
- [x] 添加迁移、编辑接口、版本号检查和历史查询。
- [x] 增加编辑按钮、编辑态、取消和历史标记。
- [x] 运行集成测试和前端构建。

### Task 6: 阶段三通知

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__notifications.sql`
- Create: `backend/src/main/java/dev/messageboard/notification/Notification.java`
- Create: `backend/src/main/java/dev/messageboard/notification/NotificationMapper.java`
- Create: `backend/src/main/java/dev/messageboard/notification/NotificationService.java`
- Create: `backend/src/main/java/dev/messageboard/notification/NotificationController.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentService.java`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.vue`
- Test: `backend/src/test/java/dev/messageboard/BoardIntegrationTest.java`

**Steps:**
- [x] 先写回复通知、去重、已读和删除过滤的失败测试。
- [x] 添加通知表、创建回复时的异步边界内写入和分页接口。
- [x] 增加顶部通知入口和最近回复入口。
- [x] 运行集成测试和前端构建。

### Task 7: 阶段三搜索和线程统计

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__comment_search_and_stats.sql`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentMapper.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentService.java`
- Modify: `backend/src/main/java/dev/messageboard/comment/CommentController.java`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.vue`
- Test: `backend/src/test/java/dev/messageboard/BoardIntegrationTest.java`

**Steps:**
- [x] 先写搜索可见性、游标分页和线程统计失败测试。
- [x] 增加 PostgreSQL 全文搜索、根线程统计和最近活动查询。
- [x] 增加搜索框、搜索结果状态和线程统计显示。
- [x] 检查删除/隐藏后的索引和统计。
- [x] 运行规模查询、集成测试和前端构建。

### Task 8: 阶段三管理员工作台

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/style.css`
- Test: `frontend/src/api.test.ts`

**Steps:**
- [x] 先写管理员入口隐藏、举报列表、治理动作和审计展示的失败测试。
- [x] 增加管理员视图，复用现有 `/api/moderation/*` 接口。
- [x] 为操作失败、权限不足和空列表增加状态展示。
- [x] 运行前端测试、构建和后端全套集成测试。

### Task 9: 最终验证和文档

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/test-report.md`
- Modify: `README.md`

**Steps:**
- [x] 运行 Maven 全套测试、前端 Vitest、生产构建和打包。
- [x] 检查迁移版本、公开接口和生产配置。
- [x] 更新三阶段完成情况和仍未覆盖的部署风险。
- [x] 记录实际命令和结果，不能用设计目标替代测试证据。

