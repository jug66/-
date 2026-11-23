import { describe, expect, it } from "vitest";
import {
  attachmentHint,
  canEditComment,
  focusActionLabel,
  replyActionLabel,
  threadStateLabel,
} from "./ui";

describe("comment UI labels", () => {
  it("uses a readable reply label when count is unavailable", () => {
    expect(replyActionLabel(0, false)).toBe("查看回复");
    expect(replyActionLabel(8, false)).toBe("查看 8 条回复");
    expect(replyActionLabel(8, true)).toBe("收起回复");
  });
  it("caps visual depth wording without hiding the branch action", () => {
    expect(focusActionLabel(0)).toBe("查看对话");
    expect(focusActionLabel(4)).toBe("继续此分支");
  });
  it("describes the attachment limit without exposing an empty state", () => {
    expect(attachmentHint(0)).toBe("可添加图片 · 0/3 · 单张不超过 5MB");
    expect(attachmentHint(2)).toBe("可继续添加 · 2/3 · 单张不超过 5MB");
  });
  it("maps thread states to user-facing labels", () => {
    expect(threadStateLabel("open")).toBe("开放讨论");
    expect(threadStateLabel("resolved")).toBe("已解决");
    expect(threadStateLabel("locked")).toBe("已锁定");
    expect(threadStateLabel("archived")).toBe("已归档");
  });
  it("only enables editing before the server deadline", () => {
    const now = Date.parse("2026-10-24T00:00:00Z");
    expect(canEditComment({ deletedAt: null, editDeadline: "2026-10-24T00:10:00Z" }, now)).toBe(true);
    expect(canEditComment({ deletedAt: null, editDeadline: "2026-10-23T23:59:00Z" }, now)).toBe(false);
    expect(canEditComment({ deletedAt: "2026-10-23T23:00:00Z", editDeadline: "2026-10-24T00:10:00Z" }, now)).toBe(false);
  });
});
