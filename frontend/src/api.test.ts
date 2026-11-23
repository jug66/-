import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, resetCsrf } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
  resetCsrf();
});
describe("API uncertainty and expired sessions", () => {
  it("reports CSRF bootstrap network failures in Chinese without sending content", async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError("Failed to fetch"));
    vi.stubGlobal("fetch", fetch);
    await expect(api("/comments", "POST", { content: "draft" })).rejects.toMatchObject({
      status: 0, message: expect.stringContaining("草稿已保留"),
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("translates a stale-CSRF session into login expiry without replaying the write", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: "token", headerName: "X-CSRF-TOKEN" }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ message: "csrf", requestId: "r1" }), {
          status: 403,
        }),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify({ user: null })));
    vi.stubGlobal("fetch", fetch);
    await expect(
      api("/comments", "POST", { content: "draft" }),
    ).rejects.toMatchObject({ status: 401, requestId: "r1" });
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(
      fetch.mock.calls.filter((args) => args[1]?.method === "POST"),
    ).toHaveLength(1);
  });
  it("does not claim failure or clear content on a network timeout after submission", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: "token", headerName: "X-CSRF-TOKEN" }),
        ),
      )
      .mockRejectedValueOnce(new DOMException("timeout", "TimeoutError"));
    vi.stubGlobal("fetch", fetch);
    await expect(
      api("/comments", "POST", { content: "draft" }),
    ).rejects.toMatchObject({
      status: 0,
      message: expect.stringContaining("尚未确认操作结果"),
    });
  });
  it("keeps authorization denial for a logged-in user distinct from expired login", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ token: "token", headerName: "X-CSRF-TOKEN" }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ message: "no permission" }), {
          status: 403,
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ user: { id: "another" } })),
      );
    vi.stubGlobal("fetch", fetch);
    await expect(api("/comments/id", "DELETE")).rejects.toBeInstanceOf(
      ApiError,
    );
    expect(fetch).toHaveBeenCalledTimes(3);
  });
});
