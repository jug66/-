package dev.messageboard.comment;

import dev.messageboard.common.*;
import dev.messageboard.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class CommentService {
    private final CommentMapper mapper;
    private final CursorCodec cursors;
    private final AttachmentService attachments;
    private final ModerationService moderation;
    private final NotificationService notifications;
    public CommentService(CommentMapper mapper,CursorCodec cursors,AttachmentService attachments,ModerationService moderation,
                          NotificationService notifications) {
        this.mapper=mapper; this.cursors=cursors; this.attachments=attachments; this.moderation=moderation; this.notifications=notifications;
    }
    public record Page(List<Comment> items,String nextCursor,boolean hasMore) {}
    public record ThreadStats(long totalReplies, java.time.OffsetDateTime lastActivityAt) {}
    public record Context(Comment target,List<Comment> ancestors,Comment parent,Page replies,
                          boolean hasMoreAncestors,String ancestorCursor) {
        public Context(Comment target,List<Comment> ancestors,Comment parent,Page replies) {
            this(target,ancestors,parent,replies,false,null);
        }
    }
    public record Create(String content,UUID parentId,UUID requestId,List<UUID> attachmentIds) {
        public Create(String content,UUID parentId,UUID requestId) { this(content,parentId,requestId,List.of()); }
    }
    public record Edit(String content, int revision) {}
    public Comment get(UUID id) {
        Comment row=mapper.find(id);
        if(row==null) throw new ApiException(404,"这条留言不存在");
        return row.withAttachments(attachments.forComments(List.of(id)).getOrDefault(id,List.of()));
    }
    public Context context(UUID id,int ancestorLimit,int replyLimit,String cursor) {
        return context(id, ancestorLimit, replyLimit, cursor, null);
    }
    public Context context(UUID id,int ancestorLimit,int replyLimit,String cursor,String ancestorCursor) {
        if (ancestorLimit < 1 || ancestorLimit > 20) throw ApiException.bad("祖先留言条数应为 1～20");
        if (replyLimit < 1 || replyLimit > 100) throw ApiException.bad("每页条数应为 1～100");
        Comment target=mapper.find(id);
        if (target==null) throw new ApiException(404,"这条留言不存在");
        List<Comment> raw;
        if (ancestorCursor == null) {
            raw = mapper.ancestors(id, ancestorLimit + 1);
        } else {
            UUID start = cursors.decodeAncestor(ancestorCursor, id);
            if (start.equals(id) || !mapper.isAncestor(id, start))
                throw ApiException.bad("祖先游标不属于当前留言");
            raw = mapper.ancestorPage(start, ancestorLimit + 1);
        }
        boolean more = raw.size() > ancestorLimit;
        List<Comment> rows = raw.subList(0, Math.min(ancestorLimit, raw.size()));
        UUID next = more ? rows.getFirst().parentId() : null;
        List<UUID> ids=new ArrayList<>(); ids.add(id); ids.addAll(rows.stream().map(Comment::id).toList());
        var images=attachments.forComments(ids);
        target=target.withAttachments(images.getOrDefault(id,List.of()));
        var ancestors=rows.stream().map(row -> row.withAttachments(images.getOrDefault(row.id(),List.of()))).toList();
        UUID parentId=target.parentId();
        Comment parent=parentId==null?null:ancestors.stream().filter(row -> row.id().equals(parentId)).findFirst().orElseGet(() -> {
            Comment direct=mapper.find(parentId);
            return direct==null?null:direct.withAttachments(images.getOrDefault(direct.id(),List.of()));
        });
        return new Context(target,ancestors,parent,page(id,"oldest",replyLimit,cursor),
            more && next != null, more && next != null ? cursors.encodeAncestor(id,next) : null);
    }

    public Page recentReplies(int limit, String cursor) {
        if (limit < 1 || limit > 100) throw ApiException.bad("每页条数应为 1～100");
        var position = cursors.decodeRecent(cursor);
        var rows = mapper.recent(position.time(), position.id(), limit + 1);
        boolean more = rows.size() > limit;
        var pageRows = rows.subList(0, Math.min(limit, rows.size()));
        var images = attachments.forComments(pageRows.stream().map(Comment::id).toList());
        var items = pageRows.stream().map(row -> row.withAttachments(images.getOrDefault(row.id(), List.of()))).toList();
        return new Page(items, more ? cursors.encodeRecent(items.getLast()) : null, more);
    }

    public Page search(String query, int limit, String cursor) {
        String value = query == null ? "" : query.strip();
        Checks.text(value, 1, 80, "搜索关键词");
        var position = cursors.decodeSearch(cursor, value);
        var rows = mapper.search(value, position.time(), position.id(), limit + 1);
        boolean more = rows.size() > limit;
        var pageRows = rows.subList(0, Math.min(limit, rows.size()));
        var images = attachments.forComments(pageRows.stream().map(Comment::id).toList());
        var items = pageRows.stream().map(row -> row.withAttachments(images.getOrDefault(row.id(), List.of()))).toList();
        return new Page(items, more ? cursors.encodeSearch(value, items.getLast()) : null, more);
    }
    public ThreadStats stats(UUID id) {
        Comment row=mapper.find(id);
        if (row == null) throw new ApiException(404, "这条留言不存在");
        var stats=mapper.threadStats(row.rootId());
        return new ThreadStats(stats.totalReplies(), stats.lastActivityAt());
    }

    @Transactional(timeout=10)
    public Comment edit(UUID user, UUID id, Edit data) {
        if (data == null) throw ApiException.bad("缺少编辑内容");
        Checks.text(data.content(), 1, 2000, "留言");
        if (Checks.blank(data.content())) throw ApiException.bad("留言不能全为空白");
        var row = mapper.lock(id);
        if (row == null) throw new ApiException(404, "这条留言不存在");
        if (!row.authorId().equals(user)) throw new ApiException(403, "只能编辑自己的留言");
        if (row.deletedAt() != null) throw new ApiException(409, "已删除的留言不能编辑");
        if (row.moderationState() != null && !"visible".equals(row.moderationState()))
            throw new ApiException(409, "这条留言暂不可编辑");
        var current = mapper.find(id);
        if (current == null || current.editDeadline() == null || current.editDeadline().isBefore(OffsetDateTime.now(ZoneOffset.UTC)))
            throw new ApiException(409, "留言已超过可编辑时间");
        if (current.revision() != data.revision()) throw new ApiException(409, "留言已经被修改，请刷新后重试");
        mapper.insertRevision(UUID.randomUUID(), id, user, current.revision(), current.content());
        if (mapper.updateContent(id, data.content().strip(), data.revision()) != 1)
            throw new ApiException(409, "留言已经被修改，请刷新后重试");
        return get(id);
    }

    public List<CommentRevision> revisions(UUID user, UUID id) {
        var row = mapper.find(id);
        if (row == null) throw new ApiException(404, "这条留言不存在");
        if (!row.userId().equals(user)) throw new ApiException(403, "只能查看自己的编辑历史");
        return mapper.revisions(id);
    }
    public Page page(UUID parent,String sort,int limit,String cursor) {
        if(limit<1 || limit>100) throw ApiException.bad("每页条数应为 1～100");
        if(!Set.of("newest","oldest").contains(sort) || parent!=null && !sort.equals("oldest")) throw ApiException.bad("不支持的排序方式");
        if(parent!=null && mapper.find(parent)==null) throw new ApiException(404,"这条留言不存在");
        boolean newest=sort.equals("newest");
        var position=cursors.decode(cursor,parent,newest);
        var rows=mapper.page(parent,newest,position.time(),position.id(),limit+1);
        boolean more=rows.size()>limit;
        var pageRows=rows.subList(0,Math.min(limit,rows.size()));
        var images=attachments.forComments(pageRows.stream().filter(row -> row.deletedAt()==null).map(Comment::id).toList());
        var items=pageRows.stream().map(row -> row.withAttachments(images.getOrDefault(row.id(),List.of()))).toList();
        return new Page(items,more?cursors.encode(parent,newest,items.getLast()):null,more);
    }
    @Transactional(timeout=10)
    public Comment create(UUID user,Create data) {
        moderation.requireActive(user);
        Checks.text(data.content(),1,2000,"留言");
        if(Checks.blank(data.content())) throw ApiException.bad("留言不能全为空白");
        if(data.requestId()==null) throw ApiException.bad("缺少请求编号");
        List<UUID> imageIds=data.attachmentIds()==null?List.of():data.attachmentIds();
        if(imageIds.size()>3 || imageIds.stream().anyMatch(Objects::isNull) || new HashSet<>(imageIds).size()!=imageIds.size())
            throw ApiException.bad("每条留言最多附带 3 张不同的图片");
        // 空附件保留 V1 的摘要，升级后旧的文字提交仍能幂等重放。
        String hash=digest(data.parentId()+"\n"+data.content()+(imageIds.isEmpty()?"":"\nattachments:"+imageIds));
        mapper.lockRequest(user+":"+data.requestId());
        var previous=mapper.request(user,data.requestId());
        if(previous!=null) {
            if(!previous.payloadHash().equals(hash)) throw new ApiException(409,"请求编号已用于其他内容，请重新编辑后提交");
            return get(previous.commentId());
        }
        UUID id=UUID.randomUUID(),root=id;
        Comment parentForNotification=null;
        if(data.parentId()!=null) {
            var parent=mapper.lock(data.parentId());
            if(parent==null) throw new ApiException(404,"回复的留言不存在，草稿已保留");
            if(parent.deletedAt()!=null) throw new ApiException(409,"这条留言已删除，不能直接回复，草稿已保留");
            moderation.ensureThreadOpen(parent.rootId());
            if("hidden".equals(parent.moderationState())) throw new ApiException(409,"这条留言暂不可回复，草稿已保留");
            root=parent.rootId();
            parentForNotification=mapper.find(parent.id());
        }
        mapper.insert(id,user,data.parentId(),root,data.content());
        attachments.attach(user,id,imageIds);
        notifications.reply(user,parentForNotification,id);
        mapper.recordRequest(user,data.requestId(),hash,id);
        return get(id);
    }
    @Transactional(timeout=10)
    public Comment delete(UUID user,UUID id) {
        var row=mapper.lock(id);
        if(row==null) throw new ApiException(404,"这条留言不存在");
        if(!row.authorId().equals(user)) throw new ApiException(403,"只能删除自己的留言");
        mapper.delete(id);
        attachments.deleteForComment(id);
        return get(id);
    }
    private String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
