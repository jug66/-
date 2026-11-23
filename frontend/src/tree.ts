import type { Comment } from "./api";

export interface Branch {
  items: Comment[];
  nextCursor: string | null;
  cursor: string | null;
  history: (string | null)[];
  loaded: boolean;
  loading: boolean;
  error: string;
  open: boolean;
  lastUsedAt: number;
}
export type Row =
  | { kind: "comment"; comment: Comment; depth: number }
  | { kind: "branch"; parentId: string; depth: number };

/** 栈式遍历，深链不会消耗 JavaScript 调用栈；卡片数严格受 budget 限制。 */
export function visibleRows(
  roots: Comment[],
  branches: Map<string, Branch>,
  budget = 200,
) {
  const stack: Row[] = [...roots]
    .reverse()
    .map((comment) => ({ kind: "comment", comment, depth: 0 }));
  const rows: Row[] = [];
  let count = 0;
  const seen = new Set<string>();
  while (stack.length && count < budget) {
    const row = stack.pop()!;
    if (row.kind === "branch") {
      rows.push(row);
      continue;
    }
    if (seen.has(row.comment.id)) continue;
    seen.add(row.comment.id);
    rows.push(row);
    count++;
    const branch = branches.get(row.comment.id);
    if (branch?.open) {
      stack.push({
        kind: "branch",
        parentId: row.comment.id,
        depth: row.depth + 1,
      });
      for (let i = branch.items.length - 1; i >= 0; i--)
        stack.push({
          kind: "comment",
          comment: branch.items[i]!,
          depth: row.depth + 1,
        });
    }
  }
  return { rows, capped: stack.length > 0 };
}
export function emptyBranch(): Branch {
  return {
    items: [],
    nextCursor: null,
    cursor: null,
    history: [],
    loaded: false,
    loading: false,
    error: "",
    open: true,
    lastUsedAt: 0,
  };
}
export function unique(items: Comment[]) {
  return [...new Map(items.map((row) => [row.id, row])).values()];
}
export class Submission {
  private signature = "";
  private key = "";
  for(content: string, parentId: string | null, attachmentIds: string[] = []) {
    const signature = JSON.stringify([content, parentId, attachmentIds]);
    if (this.signature !== signature) {
      this.signature = signature;
      this.key = crypto.randomUUID();
    }
    return this.key;
  }
  clear() {
    this.signature = "";
    this.key = "";
  }
}
