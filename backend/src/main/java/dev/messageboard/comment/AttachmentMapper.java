package dev.messageboard.comment;

import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.*;

@Mapper
public interface AttachmentMapper {
    String FIELDS = "id,owner_id,batch_id,comment_id,position,storage_key,original_name,media_type,byte_size,width,height,created_at";
    record Stored(UUID id, UUID ownerId, UUID batchId, UUID commentId, Integer position, String storageKey,
                  String originalName, String mediaType, long byteSize, int width, int height,
                  OffsetDateTime createdAt) {}

    @Select("SELECT " + FIELDS + " FROM comment_attachment WHERE owner_id=#{owner} AND batch_id=#{batch} AND deleted_at IS NULL ORDER BY position,id")
    List<Stored> byBatch(@Param("owner") UUID owner, @Param("batch") UUID batch);
    @Select("SELECT COALESCE(sum(byte_size),0) FROM comment_attachment WHERE owner_id=#{owner} AND comment_id IS NULL AND deleted_at IS NULL AND expires_at>clock_timestamp()")
    long pendingBytes(UUID owner);
    @Insert("""
        INSERT INTO comment_attachment(id,owner_id,batch_id,position,storage_key,original_name,media_type,byte_size,width,height,expires_at)
        VALUES(#{row.id},#{row.ownerId},#{row.batchId},#{row.position},#{row.storageKey},#{row.originalName},#{row.mediaType},#{row.byteSize},#{row.width},#{row.height},clock_timestamp()+interval '24 hours')
        """)
    void save(@Param("row") Stored row);
    @Select({"<script>", "SELECT " + FIELDS + " FROM comment_attachment WHERE owner_id=#{owner}",
        "AND comment_id IS NULL AND deleted_at IS NULL AND expires_at>clock_timestamp() AND id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "ORDER BY position FOR UPDATE", "</script>"})
    List<Stored> lockPending(@Param("owner") UUID owner, @Param("ids") List<UUID> ids);
    @Update("UPDATE comment_attachment SET comment_id=#{comment},position=#{position},expires_at=NULL WHERE id=#{id} AND owner_id=#{owner} AND comment_id IS NULL AND deleted_at IS NULL AND expires_at>clock_timestamp()")
    int attach(@Param("owner") UUID owner, @Param("comment") UUID comment, @Param("id") UUID id, @Param("position") int position);
    @Select({"<script>", "SELECT a.id,a.owner_id,a.batch_id,a.comment_id,a.position,a.storage_key,a.original_name,a.media_type,a.byte_size,a.width,a.height,a.created_at",
        "FROM comment_attachment a JOIN comment c ON c.id=a.comment_id",
        "WHERE c.deleted_at IS NULL AND c.moderation_state='visible' AND a.deleted_at IS NULL AND a.comment_id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "ORDER BY a.comment_id,a.position,a.id", "</script>"})
    List<Stored> byComments(@Param("ids") List<UUID> ids);
    @Select("""
        SELECT a.id,a.owner_id,a.batch_id,a.comment_id,a.position,a.storage_key,a.original_name,a.media_type,a.byte_size,a.width,a.height,a.created_at
        FROM comment_attachment a LEFT JOIN comment c ON c.id=a.comment_id
        WHERE a.id=#{id} AND a.deleted_at IS NULL AND
        ((a.comment_id IS NOT NULL AND c.deleted_at IS NULL AND c.moderation_state='visible') OR
         (a.comment_id IS NULL AND a.owner_id=#{owner} AND a.expires_at>clock_timestamp()))
        """)
    Stored visible(@Param("id") UUID id, @Param("owner") UUID owner);
    @Select("SELECT " + FIELDS + " FROM comment_attachment WHERE owner_id=#{owner} AND id=#{id} AND comment_id IS NULL AND deleted_at IS NULL FOR UPDATE")
    Stored lockPendingOne(@Param("owner") UUID owner, @Param("id") UUID id);
    @Update("UPDATE comment_attachment SET deleted_at=clock_timestamp() WHERE id=#{id} AND comment_id IS NULL AND deleted_at IS NULL")
    int deletePending(UUID id);
    @Update("UPDATE comment_attachment SET deleted_at=clock_timestamp() WHERE comment_id=#{comment} AND deleted_at IS NULL")
    int deleteForComment(UUID comment);
    @Select("""
        SELECT id,owner_id,batch_id,comment_id,position,storage_key,original_name,media_type,byte_size,width,height,created_at
        FROM comment_attachment
        WHERE purged_at IS NULL AND (deleted_at IS NOT NULL OR (comment_id IS NULL AND expires_at<=clock_timestamp()))
        ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
        """)
    List<Stored> cleanupCandidates();
    @Update("UPDATE comment_attachment SET deleted_at=COALESCE(deleted_at,clock_timestamp()),purged_at=clock_timestamp() WHERE id=#{id}")
    int markPurged(UUID id);
}
