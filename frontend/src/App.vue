<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import {
  api,
  ApiError,
  resetCsrf,
  type Comment,
  type Attachment,
  type CommentContext,
  type Page,
  type User,
  type Notification,
  type ModerationReport,
  type ModerationAudit,
  type ThreadStats,
} from "./api";
import {
  emptyBranch,
  Submission,
  unique,
  visibleRows,
  type Branch,
} from "./tree";
import {
  attachmentHint,
  canEditComment,
  focusActionLabel,
  replyActionLabel,
  threadStateLabel,
} from "./ui";

const user = ref<User | null>(null);
let lastIdentity: string | null = null;
const sort = ref<"newest" | "oldest">("newest");
const root = reactive(emptyBranch());
const branches = reactive(new Map<string, Branch>());
const focus = ref<Comment | null>(null);
const breadcrumbs = ref<Comment[]>([]);
const ancestorCursor = ref<string | null>(null);
const hasMoreAncestors = ref(false);
const backIds = ref<(string | null)[]>([]);
const draft = ref("");
const attachments = ref<Attachment[]>([]);
const uploadBatch = ref(crypto.randomUUID());
const uploading = ref(false);
const target = ref<Comment | null>(null);
const sending = ref(false);
const notice = ref("");
const failure = ref("");
const confirmed = ref<Comment | null>(null);
const submission = new Submission();
const composer = ref<HTMLTextAreaElement>();
const fileInput = ref<HTMLInputElement>();
const authDialog = ref<HTMLDialogElement>();
const deleteDialog = ref<HTMLDialogElement>();
const loginInput = ref<HTMLInputElement>();
const authMode = ref<"login" | "register" | "forgot" | "reset">("login");
const auth = reactive({
  loginName: "",
  displayName: "",
  email: "",
  password: "",
  confirmPassword: "",
  resetToken: "",
});
const authBusy = ref(false);
const authError = ref("");
const deleting = ref<Comment | null>(null);
const deleteBusy = ref(false);
const deleteError = ref("");
const editingId = ref<string | null>(null);
const editDraft = ref("");
const editRevision = ref(0);
const editBusy = ref(false);
const editError = ref("");
const historyId = ref<string | null>(null);
const historyRows = ref<Array<{ revision: number; content: string; createdAt: string }>>([]);
const historyLoading = ref(false);
const recentOpen = ref(false);
const recentLoading = ref(false);
const recentItems = ref<Comment[]>([]);
const recentCursor = ref<string | null>(null);
const searchTerm = ref("");
const searchOpen = ref(false);
const searchLoading = ref(false);
const searchItems = ref<Comment[]>([]);
const notifications = ref<Notification[]>([]);
const notificationOpen = ref(false);
const notificationLoading = ref(false);
const admin = ref(false);
const adminOpen = ref(false);
const reports = ref<ModerationReport[]>([]);
const audits = ref<ModerationAudit[]>([]);
const adminLoading = ref(false);
const threadStats = ref<ThreadStats | null>(null);
let generation = 0;
let focusRequest = 0;
let branchClock = 0;
const view = computed(() =>
  visibleRows(focus.value ? [focus.value] : root.items, branches),
);
const count = computed(() => Array.from(draft.value).length);
const initial = (name?: string) => Array.from(name || "你")[0];
const excerpt = (value: string | null, max = 72) => {
  const text = (value || "该留言已删除").replace(/\s+/g, " ").trim();
  return text.length > max ? `${text.slice(0, max)}…` : text;
};
const canSend = computed(
  () =>
    count.value > 0 &&
    count.value <= 2000 &&
    draft.value.trim().length > 0 &&
    !sending.value && !uploading.value,
);
const fullDate = (value: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZoneName: "short",
  }).format(new Date(value));
const shortDate = (value: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));

async function copyCommentLink(id: string) {
  const url = new URL(window.location.href);
  url.searchParams.set("focus", id);
  try {
    await navigator.clipboard.writeText(url.toString());
    notice.value = "留言链接已复制，可以直接分享这段对话。";
  } catch {
    notice.value = url.toString();
  }
}
function replaceComment(updated: Comment) {
  root.items = root.items.map((row) => row.id === updated.id ? updated : row);
  for (const state of branches.values()) state.items = state.items.map((row) => row.id === updated.id ? updated : row);
  if (focus.value?.id === updated.id) focus.value = updated;
  if (target.value?.id === updated.id) target.value = updated;
}
function startEdit(comment: Comment) {
  editingId.value = comment.id;
  editDraft.value = comment.content || "";
  editRevision.value = comment.revision || 0;
  editError.value = "";
}
function cancelEdit() {
  editingId.value = null;
  editDraft.value = "";
  editError.value = "";
}
async function saveEdit(comment: Comment) {
  if (editBusy.value) return;
  editBusy.value = true;
  editError.value = "";
  try {
    const updated = await api<Comment>(`/comments/${comment.id}`, "PUT", {
      content: editDraft.value,
      revision: editRevision.value,
    });
    replaceComment(updated);
    cancelEdit();
    notice.value = "留言已更新。";
  } catch (error) {
    editError.value = message(error);
  } finally {
    editBusy.value = false;
  }
}
async function loadHistory(comment: Comment) {
  if (historyId.value === comment.id) {
    historyId.value = null;
    return;
  }
  historyId.value = comment.id;
  historyLoading.value = true;
  try {
    historyRows.value = await api<Array<{ revision: number; content: string; createdAt: string }>>(`/comments/${comment.id}/revisions`);
  } catch (error) {
    failure.value = message(error);
  } finally {
    historyLoading.value = false;
  }
}

function message(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    user.value = null;
    showAuth();
  }
  return error instanceof Error ? error.message : "操作失败，请重试";
}
function branch(id: string) {
  let state = branches.get(id);
  if (!state) {
    if (branches.size >= 40) evictBranch(id);
    state = reactive(emptyBranch());
    branches.set(id, state);
  }
  state.lastUsedAt = ++branchClock;
  return state;
}
function protectedBranchIds() {
  const visible = view.value.rows
    .filter((row): row is Extract<(typeof view.value.rows)[number], { kind: "comment" }> => row.kind === "comment")
    .map((row) => row.comment.id);
  return new Set([
    focus.value?.id,
    ...breadcrumbs.value.map((row) => row.id),
    ...visible.filter((id) => branches.get(id)?.open),
  ].filter(Boolean) as string[]);
}
function evictBranch(exclude?: string) {
  const protectedIds = protectedBranchIds();
  const candidates = [...branches.entries()]
    .filter(([id]) => id !== exclude && !protectedIds.has(id))
    .sort((a, b) => a[1].lastUsedAt - b[1].lastUsedAt);
  const victim = candidates[0]?.[0];
  if (victim) { branches.delete(victim); return true; }
  return false;
}
async function load(
  parentId: string | null = null,
  direction: "first" | "next" | "prev" | "retry" = "first",
) {
  const state = parentId ? branch(parentId) : root;
  if (parentId) state.lastUsedAt = ++branchClock;
  if (state.loading) return;
  const token = generation;
  let cursor =
    direction === "next"
      ? state.nextCursor
      : direction === "prev"
        ? (state.history.at(-1) ?? null)
        : direction === "retry"
          ? state.cursor
          : null;
  if (direction === "next" && !cursor) return;
  state.loading = true;
  state.error = "";
  try {
    const params = new URLSearchParams({ limit: "20" });
    if (!parentId) params.set("sort", sort.value);
    if (cursor) params.set("cursor", cursor);
    const data = await api<Page>(
      (parentId ? `/comments/${parentId}/replies` : "/comments") + "?" + params,
    );
    if (token !== generation) return;
    if (direction === "next")
      state.history = [...state.history, state.cursor].slice(-50);
    if (direction === "prev") state.history.pop();
    if (direction === "first") state.history = [];
    state.cursor = cursor;
    state.items = unique(data.items);
    state.nextCursor = data.nextCursor;
    state.loaded = true;
  } catch (error) {
    if (token === generation) state.error = message(error);
  } finally {
    if (token === generation) state.loading = false;
  }
}
async function toggle(row: Comment) {
  const existing = branches.get(row.id);
  if (existing?.open) {
    existing.open = false;
    return;
  }
  if (!existing && branches.size >= 40 && !evictBranch(row.id)) {
      // 不回收正在阅读的祖先，否则深链展开到缓存上限时会整条消失。
      await setFocus(row.id);
      if (focus.value?.id === row.id)
        notice.value = "已聚焦当前分支，可继续向下展开；点击上级作者可返回。";
      return;
  }
  const state = branch(row.id);
  state.open = true;
  if (!state.loaded) await load(row.id);
}
async function setFocus(id: string | null, remember = true, updateUrl = true) {
  const request = ++focusRequest;
  failure.value = "";
  try {
    const context = id
      ? await api<CommentContext>(`/comments/${id}/context?ancestorLimit=8&limit=20`)
      : null;
    if (request !== focusRequest) return;
    if (remember)
      backIds.value = [...backIds.value, focus.value?.id ?? null].slice(-100);
    if (updateUrl) {
      const url = new URL(window.location.href);
      if (id) url.searchParams.set("focus", id);
      else url.searchParams.delete("focus");
      if (remember) window.history.pushState({}, "", url);
      else window.history.replaceState({}, "", url);
    }
    generation++;
    branches.clear();
    root.loading = false;
    focus.value = context?.target ?? null;
    threadStats.value = null;
    if (context?.target) void loadStats(context.target);
    breadcrumbs.value = context?.ancestors ?? [];
    ancestorCursor.value = context?.ancestorCursor ?? null;
    hasMoreAncestors.value = context?.hasMoreAncestors ?? false;
    if (context) {
      const state = branch(context.target.id);
      state.items = unique(context.replies.items);
      state.nextCursor = context.replies.nextCursor;
      state.cursor = null;
      state.history = [];
      state.loaded = true;
      state.open = true;
    } else {
      breadcrumbs.value = [];
      await load();
    }
    window.scrollTo({ top: 0, behavior: "smooth" });
  } catch (error) {
    failure.value = message(error);
  }
}
async function loadMoreAncestors() {
  if (!focus.value || !ancestorCursor.value || !hasMoreAncestors.value) return;
  try {
    const data = await api<CommentContext>(
      `/comments/${focus.value.id}/context?ancestorLimit=8&limit=20&ancestorCursor=${encodeURIComponent(ancestorCursor.value)}`,
    );
    const existing = new Set(breadcrumbs.value.map((row) => row.id));
    breadcrumbs.value = [
      ...data.ancestors.filter((row) => !existing.has(row.id)),
      ...breadcrumbs.value,
    ];
    ancestorCursor.value = data.ancestorCursor ?? null;
    hasMoreAncestors.value = data.hasMoreAncestors ?? false;
  } catch (error) {
    failure.value = message(error);
  }
}
async function loadRecent() {
  if (recentLoading.value) return;
  recentOpen.value = true;
  recentLoading.value = true;
  try {
    const data = await api<Page>("/comments/recent-replies?limit=8");
    recentItems.value = data.items;
    recentCursor.value = data.nextCursor;
  } catch (error) {
    failure.value = message(error);
  } finally {
    recentLoading.value = false;
  }
}
async function runSearch() {
  const query = searchTerm.value.trim();
  if (!query) { searchOpen.value = false; return; }
  searchLoading.value = true;
  try {
    const data = await api<Page>(`/comments/search?q=${encodeURIComponent(query)}&limit=20`);
    searchItems.value = data.items;
    searchOpen.value = true;
  } catch (error) { failure.value = message(error); }
  finally { searchLoading.value = false; }
}
async function loadNotifications() {
  if (!user.value || notificationLoading.value) return;
  notificationOpen.value = true;
  notificationLoading.value = true;
  try { notifications.value = (await api<{ items: Notification[] }>("/notifications?limit=20")).items; }
  catch (error) { failure.value = message(error); }
  finally { notificationLoading.value = false; }
}
async function markNotification(item: Notification) {
  if (!item.readAt) {
    try { await api(`/notifications/${item.id}/read`, "POST"); item.readAt = new Date().toISOString(); }
    catch (error) { failure.value = message(error); }
  }
  notificationOpen.value = false;
  await setFocus(item.commentId);
}
async function loadAdminStatus() {
  if (!user.value) { admin.value = false; return; }
  try { admin.value = (await api<{ admin: boolean }>("/moderation/me")).admin; }
  catch { admin.value = false; }
}
async function loadAdmin() {
  if (!admin.value || adminLoading.value) return;
  adminOpen.value = true;
  adminLoading.value = true;
  try {
    const [reportRows, auditRows] = await Promise.all([
      api<ModerationReport[]>("/moderation/reports"),
      api<ModerationAudit[]>("/moderation/audit"),
    ]);
    reports.value = reportRows;
    audits.value = auditRows;
  } catch (error) { failure.value = message(error); }
  finally { adminLoading.value = false; }
}
async function resolveReport(report: ModerationReport, status: "resolved" | "dismissed") {
  try {
    if (status === "resolved") await api(`/moderation/comments/${report.commentId}`, "POST", { hidden: true, reason: report.reason });
    await api(`/moderation/reports/${report.id}`, "POST", { status, reason: status === "resolved" ? "已隐藏留言" : "审核后忽略" });
    reports.value = reports.value.filter((row) => row.id !== report.id);
  } catch (error) { failure.value = message(error); }
}
async function loadStats(comment: Comment) {
  try { threadStats.value = await api<ThreadStats>(`/comments/${comment.rootId}/stats`); }
  catch { threadStats.value = null; }
}
async function goBack() {
  const id = backIds.value.pop();
  await setFocus(id ?? null, false);
}
async function changeSort() {
  generation++;
  branches.clear();
  root.loading = false;
  root.items = [];
  root.loaded = false;
  await load();
}
function showAuth(mode: "login" | "register" | "forgot" | "reset" = "login") {
  authMode.value = mode;
  authError.value = "";
  if (!authDialog.value?.open) authDialog.value?.showModal();
  nextTick(() => loginInput.value?.focus());
}
async function authenticate() {
  if (authBusy.value) return;
  authBusy.value = true;
  authError.value = "";
  try {
    if (authMode.value === "forgot") {
      await api<{ message: string }>("/auth/forgot-password", "POST", {
        loginOrEmail: auth.loginName,
      });
      authDialog.value?.close();
      notice.value = "如果账号存在且已验证邮箱，我们会发送密码重置邮件。";
      return;
    }
    if (authMode.value === "reset") {
      await api<{ message: string }>("/auth/reset-password", "POST", {
        token: auth.resetToken,
        password: auth.password,
        confirmPassword: auth.confirmPassword,
      });
      authDialog.value?.close();
      auth.password = "";
      auth.confirmPassword = "";
      notice.value = "密码已重置，请使用新密码登录。";
      return;
    }
    if (authMode.value === "register") {
      await api<User>("/auth/register", "POST", {
        loginName: auth.loginName,
        displayName: auth.displayName,
        email: auth.email,
        password: auth.password,
        confirmPassword: auth.confirmPassword,
      });
      authMode.value = "login";
      auth.confirmPassword = "";
      notice.value = "账号创建成功，请登录。若填写了邮箱，请查收验证邮件。";
      return;
    }
    const loggedIn = await api<User>("/auth/login", "POST", {
      loginName: auth.loginName,
      password: auth.password,
    });
    if (lastIdentity && lastIdentity !== loggedIn.id) clearDraft();
    user.value = loggedIn;
    lastIdentity = loggedIn.id;
    await loadAdminStatus();
    resetCsrf();
    auth.password = "";
    auth.confirmPassword = "";
    authDialog.value?.close();
    failure.value = "";
    notice.value = `欢迎回来，${loggedIn.displayName}。`;
    await nextTick();
    composer.value?.focus();
  } catch (error) {
    authError.value = error instanceof Error ? error.message : "登录失败";
  } finally {
    authBusy.value = false;
  }
}
function clearDraft() {
  draft.value = "";
  attachments.value = [];
  uploadBatch.value = crypto.randomUUID();
  target.value = null;
  submission.clear();
  confirmed.value = null;
}
async function selectFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = "";
  if (!user.value) { showAuth(); return; }
  if (!files.length) return;
  const existing = attachments.value;
  if (existing.length + files.length > 3) {
    failure.value = `当前已有 ${existing.length} 张图片，最多再选择 ${3 - existing.length} 张`;
    return;
  }
  if (files.some((file) => file.size > 5 * 1024 * 1024)) { failure.value = "单张图片不能超过 5MB"; return; }
  if (files.some((file) => !["image/jpeg", "image/png", "image/webp"].includes(file.type))) {
    failure.value = "只支持 JPG、PNG、WebP 图片"; return;
  }
  uploading.value = true;
  failure.value = "";
  const batch = crypto.randomUUID();
  uploadBatch.value = batch;
  const form = new FormData();
  for (const file of files) form.append("files", file, file.name);
  try {
    const uploaded = await api<Attachment[]>(`/uploads/images?batchId=${batch}`, "POST", form);
    attachments.value = [...existing, ...uploaded];
  } catch (error) {
    failure.value = message(error);
  } finally { uploading.value = false; }
}
async function removeAttachment(id: string) {
  attachments.value = attachments.value.filter((item) => item.id !== id);
  try { await api(`/uploads/images/${id}`, "DELETE"); } catch { /* 过期清理任务会处理失败的本地临时文件 */ }
}
async function logout() {
  try {
    await api("/auth/logout", "POST");
    user.value = null;
    lastIdentity = null;
    admin.value = false;
    adminOpen.value = false;
    clearDraft();
    resetCsrf();
    auth.password = "";
    auth.confirmPassword = "";
    notice.value = "已退出，当前草稿已清空。";
  } catch (error) {
    failure.value = message(error);
  }
}
function reply(row: Comment) {
  target.value = row;
  confirmed.value = null;
  nextTick(() => {
    composer.value?.focus();
    composer.value?.scrollIntoView({ behavior: "smooth", block: "center" });
  });
}
async function send() {
  if (!user.value) {
    showAuth();
    return;
  }
  if (!canSend.value) return;
  sending.value = true;
  failure.value = "";
  notice.value = "";
  const content = draft.value,
    parentId = target.value?.id ?? null;
  try {
    const row = await api<Comment>("/comments", "POST", {
      content,
      parentId,
      requestId: submission.for(content, parentId, attachments.value.map((item) => item.id)),
      attachmentIds: attachments.value.map((item) => item.id),
    });
    // 不把末页外的回复强插入当前分页，避免制造排序错误或跳过中间数据。
    confirmed.value = row;
    draft.value = "";
    attachments.value = [];
    uploadBatch.value = crypto.randomUUID();
    target.value = null;
    submission.clear();
    notice.value = row.deletedAt
      ? "已确认此前提交的留言，目前已被删除。"
      : "已发布。可打开这条留言，或刷新列表查看。";
    if (parentId)
      for (const page of [root, ...branches.values()]) {
        const parent = page.items.find((item) => item.id === parentId);
        if (parent) parent.hasChildren = true;
      }
    if (focus.value?.id === parentId) focus.value.hasChildren = true;
    if (!parentId && sort.value === "newest" && !focus.value) await load();
  } catch (error) {
    failure.value = message(error);
  } finally {
    sending.value = false;
  }
}
function requestDelete(row: Comment) {
  deleting.value = row;
  deleteError.value = "";
  deleteDialog.value?.showModal();
}
function replaceEverywhere(row: Comment) {
  for (const page of [root, ...branches.values()])
    page.items = page.items.map((item) => (item.id === row.id ? row : item));
  if (focus.value?.id === row.id) focus.value = row;
  if (target.value?.id === row.id) target.value = row;
  if (confirmed.value?.id === row.id) confirmed.value = row;
}
async function remove() {
  if (!deleting.value || deleteBusy.value) return;
  deleteBusy.value = true;
  try {
    const row = await api<Comment>(`/comments/${deleting.value.id}`, "DELETE");
    replaceEverywhere(row);
    deleteDialog.value?.close();
    deleting.value = null;
    notice.value = "内容已删除，已有回复保留。";
  } catch (error) {
    deleteError.value = message(error);
  } finally {
    deleteBusy.value = false;
  }
}
onMounted(async () => {
  const params = new URLSearchParams(window.location.search);
  const focusId = params.get("focus");
  const resetToken = params.get("resetToken");
  const verifyToken = params.get("verifyEmail");
  if (resetToken) { auth.resetToken = resetToken; showAuth("reset"); }
  if (verifyToken)
    api(`/auth/verify-email?token=${encodeURIComponent(verifyToken)}`)
      .then(() => { notice.value = "邮箱验证成功。"; })
      .catch((error) => { failure.value = message(error); });
  window.addEventListener("popstate", () => {
    const id = new URLSearchParams(window.location.search).get("focus");
    void setFocus(id, false, false);
  });
  await Promise.all([
    api<{ user: User | null }>("/auth/me")
      .then((data) => {
        user.value = data.user;
        lastIdentity = data.user?.id ?? null;
        return loadAdminStatus();
      })
      .catch((error) => {
        failure.value = message(error);
      }),
    focusId ? setFocus(focusId, false, false) : load(),
  ]);
});
</script>

<template>
  <header class="site-header">
    <div class="header-inner">
      <a class="brand" href="/" aria-label="回声留言板首页"
        ><span class="brand-icon">↳</span>回声<span class="brand-en"
          >ECHO</span
        ></a
      >
      <span class="header-note">让每一个想法，都有回响</span>
      <nav class="header-links" aria-label="主导航">
        <a href="#discussion">讨论</a>
        <a href="#composer">发布</a>
        <button type="button" class="header-link-button" @click="loadRecent">最近回复</button>
      </nav>
      <form class="header-search" role="search" @submit.prevent="runSearch">
        <label class="sr-only" for="header-search-input">搜索留言</label>
        <input id="header-search-input" v-model="searchTerm" placeholder="搜索留言" maxlength="80" />
        <button type="submit" aria-label="搜索" :disabled="searchLoading">⌕</button>
      </form>
      <div class="account">
        <template v-if="user"
          ><span class="avatar small">{{ initial(user.displayName) }}</span
          ><span class="account-name">{{ user.displayName }}</span
          ><button v-if="admin" class="text-button" @click="loadAdmin">管理</button>
          ><button class="text-button notification-button" @click="loadNotifications" aria-label="查看通知">通知<span v-if="notifications.some((item) => !item.readAt)" class="notification-dot"></span></button
          ><button class="text-button" @click="logout">退出</button></template
        >
        <template v-else
          ><button class="text-button" @click="showAuth()">登录</button
          ><button class="button compact" @click="showAuth('register')">
            加入讨论 <span>↗</span>
          </button></template
        >
      </div>
    </div>
  </header>
  <main class="layout">
    <section class="main-column" aria-label="留言与讨论">
      <div class="hero">
        <div class="eyebrow">
          <span class="live-dot"></span> 一个开放的交流空间
        </div>
        <h1>想法在这里相遇<span>。</span></h1>
        <p>留下一点灵感，发起一个问题，或接着别人的话聊下去。</p>
      </div>
      <section id="composer" class="composer card" aria-label="发布留言">
        <div class="composer-heading">
          <span class="avatar">{{ initial(user?.displayName) }}</span
          ><strong>{{ target ? "继续这段对话" : "今天，想聊点什么？" }}</strong
          ><span class="plain-badge">纯文本</span>
        </div>
        <div v-if="target" class="reply-target">
          <span class="reply-target-label">回复 {{ target.userName }}</span>
          <span class="reply-target-preview">“{{ excerpt(target.content) }}”</span>
          <span v-if="target.deletedAt"
            >（目标已删除，请取消回复后重新选择）</span
          ><button
            class="text-button"
            :disabled="sending"
            @click="target = null"
          >
            取消回复 ×
          </button>
        </div>
        <label class="sr-only" for="draft">留言内容</label>
        <textarea
          id="draft"
          ref="composer"
          v-model="draft"
          :disabled="sending"
          rows="4"
          placeholder="分享你的想法，也欢迎认真倾听不同的声音……"
        ></textarea>
        <div class="attachment-picker">
          <input ref="fileInput" id="image-files" type="file" accept="image/jpeg,image/png,image/webp"
            multiple :disabled="sending || uploading || !user" @change="selectFiles" />
          <label for="image-files" class="button secondary attachment-button">
            {{ uploading ? "正在处理图片…" : attachments.length ? "继续添加图片" : "添加图片" }}
          </label>
          <span class="attachment-hint">{{ attachmentHint(attachments.length) }} · JPG / PNG / WebP</span>
        </div>
        <div v-if="attachments.length" class="attachment-list" aria-label="待发布图片">
          <div v-for="image in attachments" :key="image.id" class="attachment-item">
            <img :src="image.url" :alt="image.originalName" />
            <span>{{ image.originalName }}</span>
            <button type="button" class="text-button" :disabled="sending || uploading" @click="removeAttachment(image.id)">移除</button>
          </div>
        </div>
        <div class="composer-footer">
          <span :class="{ 'over-limit': count > 2000 }"
            >{{ count }} / 2000
            <span class="desktop-hint">· 支持换行</span></span
          ><button
            class="button"
            :disabled="user ? !canSend : sending"
            @click="send"
          >
            {{ sending ? "正在确认…" : user ? "发布留言" : "登录后发布" }}
            <span v-if="!sending">↑</span>
          </button>
        </div>
      </section>
      <div v-if="notice" class="notice" role="status">
        {{ notice }}
        <button
          v-if="confirmed"
          class="text-button"
          @click="setFocus(confirmed.id)"
        >
          打开留言 ↗</button
        ><button class="dismiss" aria-label="关闭提示" @click="notice = ''">
          ×
        </button>
      </div>
      <div v-if="failure" class="error-message" role="alert">
        {{ failure }}
        <button class="text-button" @click="failure = ''">关闭</button>
      </div>
      <div id="discussion" class="discussion-header">
        <div>
          <span class="section-icon">☷</span>
          <h2>{{ focus ? "这段对话" : "所有留言" }}</h2>
          <span class="subtle-label">{{
            focus ? "聚焦浏览" : "公开讨论"
          }}</span>
          <span v-if="focus" class="thread-state-badge">{{ threadStateLabel(focus.threadState) }}</span>
          <span v-if="focus && threadStats" class="thread-stats">{{ threadStats.totalReplies }} 条回复</span>
        </div>
        <div class="list-actions">
          <button class="text-button recent-mobile" @click="loadRecent">最近回复</button>
          <button v-if="focus" class="text-button" @click="goBack">
            ← 返回
          </button>
          <template v-else
            ><label class="sr-only" for="sort">留言排序</label
            ><select id="sort" v-model="sort" @change="changeSort">
              <option value="newest">最新发布</option>
              <option value="oldest">最早发布</option>
            </select></template
          >
          <button
            class="icon-button"
            aria-label="刷新当前列表"
            @click="focus ? setFocus(focus.id, false) : changeSort()"
          >
            ↻
          </button>
        </div>
      </div>
      <section v-if="recentOpen" class="recent-panel card" aria-label="最近回复">
        <div class="recent-panel-heading">
          <strong>最近回复</strong>
          <button type="button" class="text-button" @click="recentOpen = false">关闭 ×</button>
        </div>
        <div v-if="recentLoading" class="state-panel"><span class="spinner"></span>正在加载最近回复…</div>
        <div v-else-if="!recentItems.length" class="state-panel">还没有新的回复。</div>
        <template v-else>
          <button v-for="item in recentItems" :key="item.id" type="button" class="recent-item" @click="recentOpen = false; setFocus(item.id)">
            <span class="avatar small">{{ initial(item.userName) }}</span>
            <span class="recent-item-copy"><strong>{{ item.userName }}</strong><span>{{ excerpt(item.content, 72) }}</span></span>
            <time :datetime="item.createdAt">{{ shortDate(item.createdAt) }}</time>
          </button>
        </template>
      </section>
      <section v-if="searchOpen" class="recent-panel card" aria-label="搜索结果">
        <div class="recent-panel-heading">
          <strong>搜索“{{ searchTerm }}”</strong>
          <button type="button" class="text-button" @click="searchOpen = false">关闭 ×</button>
        </div>
        <div v-if="searchLoading" class="state-panel"><span class="spinner"></span>正在搜索…</div>
        <div v-else-if="!searchItems.length" class="state-panel">没有找到可见留言。</div>
        <template v-else>
          <button v-for="item in searchItems" :key="item.id" type="button" class="recent-item" @click="searchOpen = false; setFocus(item.id)">
            <span class="avatar small">{{ initial(item.userName) }}</span>
            <span class="recent-item-copy"><strong>{{ item.userName }}</strong><span>{{ excerpt(item.content, 90) }}</span></span>
            <time :datetime="item.createdAt">{{ shortDate(item.createdAt) }}</time>
          </button>
        </template>
      </section>
      <section v-if="notificationOpen" class="recent-panel card" aria-label="通知">
        <div class="recent-panel-heading">
          <strong>通知</strong>
          <button type="button" class="text-button" @click="notificationOpen = false">关闭 ×</button>
        </div>
        <div v-if="notificationLoading" class="state-panel"><span class="spinner"></span>正在加载通知…</div>
        <div v-else-if="!notifications.length" class="state-panel">暂时没有通知。</div>
        <template v-else>
          <button v-for="item in notifications" :key="item.id" type="button" class="recent-item" :class="{ unread: !item.readAt }" @click="markNotification(item)">
            <span class="avatar small">{{ initial(item.actorName) }}</span>
            <span class="recent-item-copy"><strong>{{ item.actorName }} 回复了你</strong><span>{{ excerpt(item.preview, 90) }}</span></span>
            <time :datetime="item.createdAt">{{ shortDate(item.createdAt) }}</time>
          </button>
        </template>
      </section>
      <section v-if="adminOpen" class="admin-panel card" aria-label="管理工作台">
        <div class="recent-panel-heading"><strong>管理工作台</strong><button type="button" class="text-button" @click="adminOpen = false">关闭 ×</button></div>
        <div v-if="adminLoading" class="state-panel"><span class="spinner"></span>正在加载审核数据…</div>
        <template v-else>
          <h3>待处理举报（{{ reports.length }}）</h3>
          <div v-if="!reports.length" class="state-panel">没有待处理举报。</div>
          <div v-for="report in reports" :key="report.id" class="report-row">
            <div><strong>{{ report.reason }}</strong><time :datetime="report.createdAt">{{ fullDate(report.createdAt) }}</time><span>留言 {{ report.commentId }}</span></div>
            <div class="report-actions"><button type="button" class="button compact danger" @click="resolveReport(report, 'resolved')">隐藏留言</button><button type="button" class="button compact secondary" @click="resolveReport(report, 'dismissed')">忽略</button></div>
          </div>
          <h3>最近审计</h3>
          <div v-for="audit in audits.slice(0, 8)" :key="audit.id" class="audit-row"><span>{{ audit.action }}</span><span>{{ audit.targetType }} · {{ audit.targetId }}</span><time :datetime="audit.createdAt">{{ shortDate(audit.createdAt) }}</time></div>
        </template>
      </section>
      <div v-if="focus" class="focus-bar">
        <nav class="focus-breadcrumbs" aria-label="留言路径">
          <button v-if="hasMoreAncestors" class="text-button accent" @click="loadMoreAncestors">加载更早祖先</button>
          <button class="text-button" @click="setFocus(null)">全部留言</button>
          <span v-for="ancestor in breadcrumbs" :key="ancestor.id" class="breadcrumb-part">
            <span aria-hidden="true">/</span>
            <button class="text-button" @click="setFocus(ancestor.id)">
              {{ ancestor.userName }}：{{ excerpt(ancestor.content, 28) }}
            </button>
          </span>
          <span class="breadcrumb-current">/ 当前留言</span>
        </nav>
        <span class="focus-description">只显示当前留言及已展开的回复</span
        ><button class="text-button" @click="setFocus(null)">
          查看全部留言
        </button>
      </div>
      <div v-if="root.loading && !focus" class="state-panel" role="status">
        <span class="spinner"></span>正在加载留言…
      </div>
      <div
        v-if="root.error && !focus"
        class="state-panel error-message"
        role="alert"
      >
        {{ root.error
        }}<button class="text-button" @click="load(null, 'retry')">重试</button>
      </div>
      <div
        v-if="!focus && root.loaded && !root.items.length && !root.loading"
        class="empty-state card"
      >
        <span class="empty-symbol">“</span>
        <h3>对话，从第一句话开始</h3>
        <p>这里还没有留言。来分享你的第一个想法吧。</p>
        <button class="text-button" @click="composer?.focus()">
          写下第一条留言 ↗
        </button>
      </div>
      <div class="thread-list">
        <template
          v-for="row in view.rows"
          :key="
            row.kind === 'comment' ? row.comment.id : 'page-' + row.parentId
          "
        >
          <article
            v-if="row.kind === 'comment'"
            class="comment-card card"
            :class="{ nested: row.depth > 0, 'depth-capped': row.depth >= 4, deleted: row.comment.deletedAt }"
            :style="{ '--depth': Math.min(row.depth, 4) }"
            :data-comment-id="row.comment.id"
          >
            <div class="comment-top">
              <span
                class="avatar"
                :class="{ mint: row.comment.userId !== user?.id }"
                >{{ initial(row.comment.userName) }}</span
              >
              <div class="author-block">
                <div>
                  <strong>{{ row.comment.userName }}</strong
                  ><span v-if="row.comment.userId === user?.id" class="me-badge"
                    >我</span
                  >
                </div>
                <time
                  :datetime="row.comment.createdAt"
                  :title="fullDate(row.comment.createdAt)"
                  >{{ shortDate(row.comment.createdAt) }}</time
                >
              </div>
              <details class="details">
                <summary aria-label="查看留言信息">···</summary>
                <div class="comment-menu">
                  <button type="button" class="menu-action" @click="copyCommentLink(row.comment.id)">复制留言链接</button>
                  <span>完整时间：{{ fullDate(row.comment.createdAt) }}</span>
                  <span>留言 ID：{{ row.comment.id }}</span>
                </div>
              </details>
            </div>
            <div v-if="row.comment.parentId" class="parent-label">
              ↳ 回复
              <button
                class="text-button"
                @click="setFocus(row.comment.parentId)"
              >
                {{ row.comment.parentUserName || "上级留言" }}
              </button>
            </div>
            <p v-if="row.comment.deletedAt" class="comment-content tombstone">
              该留言已删除 <span>· 已有回复仍然保留</span>
            </p>
            <div v-else-if="editingId === row.comment.id" class="edit-box">
              <textarea v-model="editDraft" rows="4" aria-label="编辑留言"></textarea>
              <p v-if="editError" class="edit-error" role="alert">{{ editError }}</p>
              <div class="edit-actions">
                <button type="button" class="button compact" :disabled="editBusy" @click="saveEdit(row.comment)">{{ editBusy ? "保存中…" : "保存修改" }}</button>
                <button type="button" class="text-button" :disabled="editBusy" @click="cancelEdit">取消</button>
              </div>
            </div>
            <p v-else class="comment-content">{{ row.comment.content }}<span v-if="row.comment.revision" class="edited-mark">已编辑</span></p>
            <div v-if="row.comment.attachments?.length" class="comment-images">
              <img v-for="image in row.comment.attachments" :key="image.id" :src="image.url"
                loading="lazy" :alt="image.originalName" :width="image.width" :height="image.height" />
            </div>
            <div class="comment-actions">
              <button
                v-if="!row.comment.deletedAt"
                class="text-button"
                @click="reply(row.comment)"
              >
                ↩ 回复
              </button>
              <button
                v-if="row.comment.hasChildren"
                class="text-button accent"
                :aria-expanded="branches.get(row.comment.id)?.open || false"
                :aria-controls="`replies-${row.comment.id}`"
                @click="toggle(row.comment)"
              >
                {{
                  branches.get(row.comment.id)?.open
                    ? "− 收起回复"
                    : `+ ${replyActionLabel(row.comment.replyCount, false)}`
                }}
              </button>
              <button
                class="text-button focus-action"
                @click="setFocus(row.comment.id)"
              >
                {{ focusActionLabel(row.depth) }} ↗
              </button>
              <button
                v-if="user?.id === row.comment.userId && !row.comment.deletedAt"
                class="text-button delete-action"
                @click="requestDelete(row.comment)"
              >
                删除
              </button>
              <button
                v-if="user?.id === row.comment.userId && canEditComment(row.comment) && editingId !== row.comment.id"
                class="text-button"
                @click="startEdit(row.comment)"
              >编辑</button>
              <button
                v-if="user?.id === row.comment.userId && row.comment.revision"
                class="text-button"
                @click="loadHistory(row.comment)"
              >{{ historyId === row.comment.id ? "收起历史" : "编辑历史" }}</button>
            </div>
            <div v-if="historyId === row.comment.id" class="edit-history">
              <span v-if="historyLoading" class="state-panel">正在加载编辑历史…</span>
              <template v-else>
                <div v-for="history in historyRows" :key="history.revision" class="history-row">
                  <span>版本 {{ history.revision + 1 }}</span><time :datetime="history.createdAt">{{ shortDate(history.createdAt) }}</time>
                  <p>{{ history.content }}</p>
                </div>
              </template>
            </div>
          </article>
          <div
            v-else-if="branches.get(row.parentId)"
            class="branch-controls"
            :id="`replies-${row.parentId}`"
            :style="{ '--depth': Math.min(row.depth, 4) }"
          >
            <template v-if="branches.get(row.parentId)!.loading"
              ><span class="spinner"></span> 加载回复中…</template
            >
            <template v-else-if="branches.get(row.parentId)!.error"
              ><span role="alert">{{ branches.get(row.parentId)!.error }}</span
              ><button class="text-button" @click="load(row.parentId, 'retry')">
                重试
              </button></template
            >
            <template v-else-if="branches.get(row.parentId)!.loaded">
              <button
                v-if="branches.get(row.parentId)!.history.length"
                class="text-button"
                @click="load(row.parentId, 'prev')"
              >
                ← 上一页回复
              </button>
              <span v-if="!branches.get(row.parentId)!.items.length"
                >暂无回复</span
              >
              <span v-else-if="!branches.get(row.parentId)!.nextCursor"
                >该页已到最后一条回复</span
              >
              <button
                v-if="branches.get(row.parentId)!.nextCursor"
                class="text-button accent"
                @click="load(row.parentId, 'next')"
              >
                下一页回复 →
              </button>
            </template>
          </div>
        </template>
      </div>
      <div v-if="view.capped" class="notice">
        当前已展示 200 条。请收起部分回复，或点击“聚焦对话”继续查看。
      </div>
      <nav
        v-if="!focus && root.items.length"
        class="pagination"
        aria-label="留言分页"
      >
        <button
          class="button secondary"
          :disabled="!root.history.length || root.loading"
          @click="load(null, 'prev')"
        >
          ← 上一页</button
        ><span>{{
          root.nextCursor
            ? "每页 20 条 · 继续发现更多想法"
            : "已经看到最后一条了"
        }}</span
        ><button
          class="button secondary"
          :disabled="!root.nextCursor || root.loading"
          @click="load(null, 'next')"
        >
          下一页 →
        </button>
      </nav>
      <footer class="page-footer">
        <span class="footer-mark">↳</span> 每一次认真回应，都让这里更有温度。
      </footer>
    </section>
    <aside class="sidebar">
      <section class="welcome-card">
        <span class="side-kicker">HELLO, NEIGHBOR</span>
        <h2>好对话，<br />可以一直继续。</h2>
        <p>这里没有话题限制。<br />你的日常、疑问与灵感，都值得被听见。</p>
        <div class="illustration" aria-hidden="true">
          <span class="bubble one">你好，世界。</span
          ><span class="bubble two">很高兴听到你的想法 ✦</span
          ><span class="orbit">✳</span>
        </div>
        <div class="welcome-foot">
          <span class="live-dot"></span> 开放阅读 · 登录参与
        </div>
      </section>
      <section class="guide card">
        <h3>一起，让讨论更友善 <span>↗</span></h3>
        <div class="guide-item">
          <span>01</span>
          <div>
            <strong>自在表达</strong>
            <p>真实的想法，比完美的措辞更重要。</p>
          </div>
        </div>
        <div class="guide-item">
          <span>02</span>
          <div>
            <strong>认真回应</strong>
            <p>点击“回复”，让每段对话找到归属。</p>
          </div>
        </div>
        <div class="guide-item">
          <span>03</span>
          <div>
            <strong>彼此尊重</strong>
            <p>可以有不同观点，也请保留善意。</p>
          </div>
        </div>
      </section>
      <div class="side-note">
        关于这里 <span>·</span> 简单、开放、慢慢聊。<br /><small
          >回声 ECHO © {{ new Date().getFullYear() }}</small
        >
      </div>
    </aside>
  </main>
  <dialog
    ref="authDialog"
    class="modal"
    aria-labelledby="auth-title"
    @close="
      auth.password = '';
      auth.confirmPassword = '';
    "
  >
    <button
      class="modal-close"
      aria-label="关闭登录窗口"
      @click="authDialog?.close()"
    >
      ×
    </button>
    <span class="brand-icon">↳</span>
    <h2 id="auth-title">
      {{ authMode === "login" ? "欢迎回到回声" : authMode === "register" ? "从这里，加入对话" : authMode === "reset" ? "设置新密码" : "找回密码" }}
    </h2>
    <p class="modal-intro">
      {{
        authMode === "login"
          ? "登录后继续分享，当前草稿会为你保留。"
          : authMode === "register"
            ? "一个简单的账号，连接更多有趣的想法。"
            : authMode === "reset"
              ? "请输入一个新的 9～128 位密码。"
              : "输入账号或已验证的邮箱，我们会发送重置邮件。"
      }}
    </p>
    <form @submit.prevent="authenticate">
      <template v-if="authMode !== 'reset'">
      <label for="loginName">{{ authMode === 'forgot' ? '账号或邮箱' : '账号' }}</label
      ><input
        id="loginName"
        ref="loginInput"
        v-model="auth.loginName"
        autocomplete="username"
        :placeholder="authMode === 'forgot' ? '输入账号或邮箱' : '3～32 位字母、数字或下划线'"
        required
        :maxlength="authMode === 'forgot' ? 320 : 32"
      />
      <template v-if="authMode === 'register'"
        ><label for="displayName">昵称 <span>（可选）</span></label
        ><input
          id="displayName"
          v-model="auth.displayName"
          autocomplete="nickname"
          placeholder="希望大家怎么称呼你？"
        /><label for="email">邮箱 <span>（可选，用于找回密码）</span></label
        ><input
          id="email"
          v-model="auth.email"
          type="email"
          autocomplete="email"
          placeholder="name@example.com"
          maxlength="320"
      /></template>
      </template>
      <template v-if="authMode !== 'forgot'"
        ><label for="password">密码</label
      ><input
        id="password"
        v-model="auth.password"
        type="password"
        :autocomplete="
          authMode === 'login' ? 'current-password' : 'new-password'
        "
        :placeholder="
          authMode === 'register' || authMode === 'reset' ? '9～128 个字符，可使用空格' : '请输入密码'
        "
        required
      /></template>
      <template v-if="authMode === 'register' || authMode === 'reset'"
        ><label for="confirmPassword">确认密码</label
        ><input
          id="confirmPassword"
          v-model="auth.confirmPassword"
          type="password"
          autocomplete="new-password"
          required
      /></template>
      <button v-if="authMode === 'login'" type="button" class="text-button accent" @click="showAuth('forgot')">
        忘记密码？
      </button>
      <div v-if="authError" class="error-message" role="alert">
        {{ authError }}
      </div>
      <button class="button full" :disabled="authBusy">
        {{ authBusy ? "请稍候…" : authMode === "login" ? "登录" : authMode === "register" ? "创建账号" : authMode === "reset" ? "保存新密码" : "发送重置邮件" }}
      </button>
    </form>
    <p class="modal-switch">
      {{ authMode === "login" ? "还没有账号？" : authMode === "register" ? "已经有账号？" : "想起密码了？"
      }}<button
        class="text-button accent"
        @click="
          authMode = authMode === 'login' ? 'register' : 'login';
          authError = '';
        "
      >
        {{ authMode === "login" ? "注册一个" : "去登录" }}
      </button>
    </p>
  </dialog>
  <dialog
    ref="deleteDialog"
    class="modal delete-modal"
    aria-labelledby="delete-title"
  >
    <h2 id="delete-title">删除这条留言？</h2>
    <p>
      内容将被清空且无法恢复。已有回复会保留，其他人仍可继续回复未删除的子留言。
    </p>
    <div v-if="deleteError" class="error-message" role="alert">
      {{ deleteError }}
    </div>
    <div class="dialog-actions">
      <button
        class="button secondary"
        :disabled="deleteBusy"
        @click="deleteDialog?.close()"
      >
        取消</button
      ><button class="button danger" :disabled="deleteBusy" @click="remove">
        {{ deleteBusy ? "正在确认…" : "确认删除" }}
      </button>
    </div>
  </dialog>
</template>
