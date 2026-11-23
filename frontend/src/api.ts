export interface User {
  id: string;
  loginName: string;
  displayName: string;
  email: string | null;
  emailVerified: boolean;
}
export interface Attachment {
  id: string;
  url: string;
  originalName: string;
  mediaType: string;
  byteSize: number;
  width: number;
  height: number;
}
export interface Comment {
  id: string;
  userId: string;
  userName: string;
  parentId: string | null;
  rootId: string;
  parentUserName: string | null;
  content: string | null;
  createdAt: string;
  deletedAt: string | null;
  threadState?: "open" | "resolved" | "locked" | "archived";
  updatedAt?: string | null;
  editDeadline?: string | null;
  revision?: number;
  hasChildren: boolean;
  replyCount: number;
  lastReplyAt: string | null;
  attachments: Attachment[];
}
export interface Page {
  items: Comment[];
  nextCursor: string | null;
  hasMore: boolean;
}
export interface CommentContext {
  target: Comment;
  ancestors: Comment[];
  parent: Comment | null;
  replies: Page;
  hasMoreAncestors?: boolean;
  ancestorCursor?: string | null;
}
export interface Notification {
  id: string;
  recipientId: string;
  actorId: string;
  actorName: string;
  commentId: string;
  type: string;
  preview: string | null;
  createdAt: string;
  readAt: string | null;
}
export interface ModerationReport {
  id: string;
  commentId: string;
  reporterId: string;
  reason: string;
  status: string;
  createdAt: string;
}
export interface ModerationAudit {
  id: string;
  actorId: string;
  action: string;
  targetType: string;
  targetId: string;
  reason: string | null;
  createdAt: string;
}
export interface ThreadStats {
  totalReplies: number;
  lastActivityAt: string | null;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public requestId?: string,
  ) {
    super(message);
  }
}
let csrf: { token: string; headerName: string } | undefined;
export function resetCsrf() {
  csrf = undefined;
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  if (method !== "GET" && !csrf) {
    let response: Response;
    try {
      response = await fetch("/api/auth/csrf", {
        credentials: "same-origin",
        signal: AbortSignal.timeout(5000),
      });
    } catch {
      throw new ApiError(0, "连接失败，草稿已保留，请检查网络后重试");
    }
    if (!response.ok)
      throw new ApiError(response.status, "无法取得安全令牌，请稍后重试");
    csrf = await response.json();
  }
  const headers: Record<string, string> = {};
  const formData = typeof FormData !== "undefined" && body instanceof FormData;
  if (body !== undefined && !formData) headers["Content-Type"] = "application/json";
  if (method !== "GET" && csrf) headers[csrf.headerName] = csrf.token;
  let response: Response;
  try {
    response = await fetch("/api" + path, {
      method,
      credentials: "same-origin",
      headers,
      body: body === undefined ? undefined : formData ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
  } catch {
    throw new ApiError(
      0,
      method === "GET"
        ? "连接失败，请检查网络后重试"
        : "尚未确认操作结果，草稿已保留。请重试确认，请勿重复编辑提交。",
    );
  }
  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: "服务暂时不可用" }));
    if (response.status === 403 || response.status === 401) resetCsrf();
    // 过期会话的旧 CSRF token 通常先触发 403。查询身份后再提示登录，
    // 保留当前页面草稿；不自动重放写请求，也不要求刷新浏览器。
    if (response.status === 403 && path.startsWith("/comments")) {
      const identity = await fetch("/api/auth/me", {
        credentials: "same-origin",
        signal: AbortSignal.timeout(5000),
      })
        .then((res) => (res.ok ? res.json() : null))
        .catch(() => null);
      if (identity && !identity.user)
        throw new ApiError(
          401,
          "登录已过期，草稿已保留，请重新登录后提交",
          error.requestId,
        );
    }
    throw new ApiError(
      response.status,
      error.message || "操作失败",
      error.requestId,
    );
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
