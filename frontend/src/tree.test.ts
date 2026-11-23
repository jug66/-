import { describe, expect, it } from "vitest";
import { emptyBranch, Submission, unique, visibleRows } from "./tree";
import type { Comment } from "./api";
const row = (n: number): Comment => ({
  id: String(n),
  userId: "u",
  userName: "同名",
  parentId: n ? String(n - 1) : null,
  rootId: "0",
  parentUserName: null,
  content: "内容",
  createdAt: "2026-10-20T00:00:00Z",
  deletedAt: null,
  hasChildren: true,
  replyCount: 1,
  lastReplyAt: "2026-10-20T00:00:00Z",
  attachments: [],
});
describe("bounded iterative tree", () => {
  it("traverses a 10000-level chain without recursion and caps DOM rows at 200 comments", () => {
    const branches = new Map(
      Array.from({ length: 10000 }, (_, n) => [
        String(n),
        { ...emptyBranch(), items: [row(n + 1)] },
      ]),
    );
    const view = visibleRows([row(0)], branches);
    expect(view.rows.filter((r) => r.kind === "comment")).toHaveLength(200);
    expect(view.capped).toBe(true);
    const all = visibleRows([row(0)], branches, 20000);
    expect(all.rows.filter((r) => r.kind === "comment")).toHaveLength(10001);
    expect(all.capped).toBe(false);
  });
  it("preserves parent order, tombstones, independent collapse, and breaks malformed cycles", () => {
    const root = row(0),
      deleted = { ...row(1), deletedAt: "2026-10-20", content: null };
    const branches = new Map([
      ["0", { ...emptyBranch(), items: [deleted, row(4)] }],
      ["1", { ...emptyBranch(), items: [row(2)] }],
      ["2", { ...emptyBranch(), items: [root] }],
    ]);
    expect(
      visibleRows([root], branches)
        .rows.filter((r) => r.kind === "comment")
        .map((r) => r.comment.id),
    ).toEqual(["0", "1", "2", "4"]);
    branches.get("1")!.open = false;
    expect(
      visibleRows([root], branches)
        .rows.filter((r) => r.kind === "comment")
        .map((r) => r.comment.id),
    ).toEqual(["0", "1", "4"]);
  });
  it("deduplicates by identity, not display name", () =>
    expect(unique([row(1), row(2), row(1)])).toHaveLength(2));
});
describe("submission identity", () => {
  it("reuses identity on retry and rotates for changed parent, content, or completed submission", () => {
    const submission = new Submission();
    const first = submission.for("hello", null);
    expect(submission.for("hello", null)).toBe(first);
    expect(submission.for("hello", null, ["image-1"])).not.toBe(first);
    expect(submission.for("hello", "parent")).not.toBe(first);
    const second = submission.for("hello", "parent");
    expect(submission.for("hello!", "parent")).not.toBe(second);
    submission.clear();
    expect(submission.for("hello", null)).not.toBe(first);
  });
});
