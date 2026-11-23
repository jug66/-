# 使用 Codex 审核 Pull Request

## 1. 审核前先准备本地差异

如果 PR 分支是本地 `other`，先同步远程：

```powershell
git fetch origin
git diff --stat origin/main...origin/other
git diff origin/main...origin/other
```

`origin/main...origin/other` 表示只查看这次 PR 相对于 main 新增的改动。

如果 PR 已经在 GitHub 上创建，也可以直接把 PR 链接提供给 Codex。

## 2. 给 Codex 的审核提示词

可以直接复制下面的提示词：

```text
请审核这个 Pull Request：<粘贴 GitHub PR 链接>

目标分支是 main，源分支是 other。请只关注本次 PR 引入的改动，不要泛泛总结整个项目。

请按以下顺序检查：
1. 功能正确性：是否会导致留言发布、回复、删除、图片上传或登录流程出错。
2. 无限层级和查询性能：是否破坏 parent_id/root_id、分页、游标或深层聚焦逻辑。
3. 数据库：迁移是否可重复执行、是否有索引/约束问题、是否需要回滚考虑。
4. 安全：权限、CSRF、输入校验、文件上传、敏感信息和 SQL 注入风险。
5. 并发和幂等：重复请求、revision 冲突、事务边界和通知是否正确。
6. 前端：加载失败、空状态、移动端布局、键盘操作和类型错误。
7. 测试：现有测试是否覆盖本次改动，是否还缺少一个有价值的回归测试。

请只报告可以通过代码证据说明的问题，并按严重程度排序：Blocker、High、Medium、Low。
每条问题请包含：文件路径、行号、问题、可能后果、最小修复建议。
如果没有发现问题，请明确写“未发现需要阻塞合并的问题”，并列出仍未验证的部分。
```

## 3. 如何判断 Codex 的结果

- `Blocker`：安全漏洞、数据丢失、无法启动或核心流程必然失败，必须修复。
- `High`：主要功能在常见场景下错误，或数据库迁移/权限逻辑存在明显问题，通常应修复后再合并。
- `Medium`：边界场景、错误处理或性能问题，应评估是否在本 PR 处理。
- `Low`：可读性、文档或非关键体验问题，可以单独建任务。

Codex 的意见需要回到实际代码和测试中验证。不要因为它给出了“通过”就跳过测试，也不要把没有文件和行号证据的猜测当成缺陷。

## 4. 修改审核意见

如果 Codex 或人工审核提出问题：

```powershell
git switch other
# 修改代码并运行测试
git add -A
git commit -m "fix: address review feedback"
git push
```

GitHub PR 会自动显示新的提交。修复后在 PR 对应评论下说明修改的文件和验证命令。
