package dev.messageboard.comment;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 图片附件的数据库元数据；文件本体保存在配置的本地目录。 */
public record Attachment(UUID id, UUID commentId, String url, String originalName,
                         String mediaType, long byteSize, int width, int height,
                         OffsetDateTime createdAt) {}
