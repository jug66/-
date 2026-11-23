package dev.messageboard.comment;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import org.apache.ibatis.annotations.AutomapConstructor;

public record Comment(UUID id, UUID userId, String userName, UUID parentId, UUID rootId,
                      String parentUserName, String content, OffsetDateTime createdAt,
                      OffsetDateTime deletedAt, String threadState, OffsetDateTime updatedAt,
                      OffsetDateTime editDeadline, int revision, boolean hasChildren,
                      long replyCount, OffsetDateTime lastReplyAt, List<Attachment> attachments) {
    @AutomapConstructor
    public Comment(UUID id, UUID userId, String userName, UUID parentId, UUID rootId,
                   String parentUserName, String content, OffsetDateTime createdAt,
                   OffsetDateTime deletedAt, String threadState, OffsetDateTime updatedAt,
                   OffsetDateTime editDeadline, int revision, boolean hasChildren,
                   long replyCount, OffsetDateTime lastReplyAt) {
        this(id,userId,userName,parentId,rootId,parentUserName,content,createdAt,deletedAt,threadState,
                updatedAt,editDeadline,revision,hasChildren,replyCount,lastReplyAt,List.of());
    }
    public Comment(UUID id, UUID userId, String userName, UUID parentId, UUID rootId,
                   String parentUserName, String content, OffsetDateTime createdAt,
                   OffsetDateTime deletedAt, boolean hasChildren) {
        this(id,userId,userName,parentId,rootId,parentUserName,content,createdAt,deletedAt,"open",
                createdAt,null,0,hasChildren,0,null,List.of());
    }
    public Comment(UUID id, UUID userId, String userName, UUID parentId, UUID rootId,
                   String parentUserName, String content, OffsetDateTime createdAt,
                   OffsetDateTime deletedAt, boolean hasChildren, long replyCount,
                   OffsetDateTime lastReplyAt) {
        this(id,userId,userName,parentId,rootId,parentUserName,content,createdAt,deletedAt,"open",
                createdAt,null,0,hasChildren,replyCount,lastReplyAt,List.of());
    }
    public Comment withAttachments(List<Attachment> images) {
        return new Comment(id,userId,userName,parentId,rootId,parentUserName,content,createdAt,deletedAt,threadState,
                updatedAt,editDeadline,revision,hasChildren,replyCount,lastReplyAt,
                deletedAt == null ? List.copyOf(images) : List.of());
    }
}
