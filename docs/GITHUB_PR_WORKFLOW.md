# GitHub 分支与 Pull Request 操作说明

本文以当前仓库为例：

```text
仓库：https://github.com/jug66/-.git
源分支：other
目标分支：main
```

## 1. 在本地进入 other 分支

```powershell
git fetch origin
git switch other
git pull --ff-only origin other
```

如果本地还没有 `other`：

```powershell
git fetch origin
git switch --track -c other origin/other
```

## 2. 修改、检查和提交

```powershell
git status
git diff

# 修改代码后运行测试
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test.ps1

git add -A
git diff --cached
git commit -m "feat: describe the change"
```

提交前不要加入 `.env`、`.runtime`、`.tools`、`node_modules`、`target` 和 `dist`。

## 3. 推送 other 分支

```powershell
git push -u origin other
```

以后继续在 `other` 上修改时，只需要：

```powershell
git add -A
git commit -m "fix: address review comments"
git push
```

已有的 Pull Request 会自动更新，不需要重新创建。

## 4. 在 GitHub 创建 PR

打开仓库页面，点击 `Pull requests` → `New pull request`，选择：

```text
base：main
compare：other
```

确认页面显示：

```text
main  ←  other
```

然后填写标题、修改说明、测试命令和风险，点击 `Create pull request`。

本仓库已经提供 `.github/pull_request_template.md`，创建 PR 时会自动填充检查模板。

## 5. 审核通过后合并

确认检查通过、冲突已解决、审核意见已处理后，在 PR 页面选择合并方式。个人功能分支推荐 `Squash and merge`，合并后删除远程 `other` 分支。

合并后同步本地：

```powershell
git switch main
git pull --ff-only origin main
git branch -d other
git fetch --prune
```

如果 `other` 还要继续使用，不要删除它；下一次开发从最新 `main` 创建新的 `feature/...` 分支。

## 6. PR 期间同步 main

如果 `main` 在 PR 期间发生了新提交：

```powershell
git fetch origin
git switch other
git rebase origin/main
```

解决冲突后：

```powershell
git add 冲突文件
git rebase --continue
git push --force-with-lease origin other
```

只对自己独占的功能分支使用 `--force-with-lease`，不要使用普通 `--force`，也不要对 `main` 强制推送。
