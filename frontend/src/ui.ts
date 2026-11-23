export function replyActionLabel(replyCount: number, open: boolean) {
  if (open) return "收起回复";
  return replyCount > 0 ? `查看 ${replyCount} 条回复` : "查看回复";
}

export function focusActionLabel(depth: number) {
  return depth >= 4 ? "继续此分支" : "查看对话";
}

export function attachmentHint(count: number, max = 3) {
  return `${count ? "可继续添加" : "可添加图片"} · ${count}/${max} · 单张不超过 5MB`;
}

export function threadStateLabel(state?: string | null) {
  switch (state) {
    case "resolved":
      return "已解决";
    case "locked":
      return "已锁定";
    case "archived":
      return "已归档";
    default:
      return "开放讨论";
  }
}

export function canEditComment(
  comment: { deletedAt: string | null; editDeadline?: string | null },
  now = Date.now(),
) {
  return !comment.deletedAt && !!comment.editDeadline && Date.parse(comment.editDeadline) > now;
}
