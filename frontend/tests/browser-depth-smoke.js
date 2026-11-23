// Run through playwright-cli run-code --filename, against the local Vite page.
// Route fixtures test the actual Vue rendering/cache behavior, not database depth.
async (page) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const comment = (n) => ({
    id: String(n),
    userId: "fixture-user",
    userName: "深层浏览验证",
    parentId: n === 0 ? null : String(n - 1),
    rootId: "0",
    parentUserName: n === 0 ? null : "深层浏览验证",
    content: `层级 ${n}`,
    createdAt: "2026-10-20T00:00:00Z",
    deletedAt: null,
    hasChildren: n < 10001,
    replyCount: n < 10001 ? 1 : 0,
    lastReplyAt: n < 10001 ? "2026-10-20T00:00:00Z" : null,
    attachments: [],
  });
  await page.route("**/api/comments**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const match = path.match(/\/comments\/(\d+)(\/replies)?$/);
    const context = path.match(/\/comments\/(\d+)\/context$/);
    let json;
    if (context) {
      const id = Number(context[1]);
      json = { target: comment(id), ancestors: [], parent: id ? comment(id - 1) : null,
        replies: { items: [comment(id + 1)], nextCursor: null, hasMore: false },
        hasMoreAncestors: false, ancestorCursor: null };
    } else if (!match) json = { items: [comment(0)], nextCursor: null, hasMore: false };
    else if (match[2])
      json = { items: [comment(Number(match[1]) + 1)], nextCursor: null, hasMore: false };
    else json = comment(Number(match[1]));
    await route.fulfill({ json });
  });
  try {
    await page.reload();
    for (let i = 0; i < 85; i++) {
      await page.getByRole("button", { name: /\+ (展开|查看)/ }).first().click();
      await page.locator(`[data-comment-id="${i + 1}"]`).waitFor();
    }
    const ids = await page.locator("[data-comment-id]").evaluateAll(
      (nodes) => nodes.map((node) => Number(node.getAttribute("data-comment-id"))),
    );
    if (ids.length > 200 || ids[0] < 40 || !ids.includes(85))
      throw new Error(`Bounded focus regression: ${JSON.stringify(ids)}`);
    await page.setViewportSize({ width: 360, height: 800 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    if (overflow || errors.length) throw new Error(JSON.stringify({ overflow, errors }));
    return { expandedLevels: 85, visibleComments: ids.length, focusedAt: ids[0], lastVisible: ids.at(-1), horizontalOverflow: overflow, pageErrors: errors };
  } finally {
    await page.unrouteAll({ behavior: "wait" });
    await page.reload();
  }
}
