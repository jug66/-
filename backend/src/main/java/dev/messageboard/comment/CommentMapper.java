package dev.messageboard.comment;

import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.*;

@Mapper
public interface CommentMapper {
    record ThreadStats(long totalReplies, OffsetDateTime lastActivityAt) {}
    String FIELDS = """
        SELECT c.id, c.author_id AS user_id, u.display_name AS user_name, c.parent_id,c.root_id,
        pu.display_name AS parent_user_name,
        CASE WHEN c.moderation_state='hidden' THEN NULL ELSE c.content END AS content,
        c.created_at,c.deleted_at,c.thread_state,c.updated_at,c.edit_deadline,c.revision,
        COALESCE((SELECT true FROM comment ch WHERE ch.parent_id=c.id ORDER BY ch.created_at,ch.id LIMIT 1),false) AS has_children,
        (SELECT count(*) FROM comment ch WHERE ch.parent_id=c.id) AS reply_count,
        (SELECT max(ch.created_at) FROM comment ch WHERE ch.parent_id=c.id) AS last_reply_at
        FROM comment c JOIN board_user u ON u.id=c.author_id
        LEFT JOIN comment p ON p.id=c.parent_id LEFT JOIN board_user pu ON pu.id=p.author_id
        """;
    @Select("SELECT count(*) FILTER (WHERE id<>#{root} AND deleted_at IS NULL) AS total_replies, max(created_at) AS last_activity_at FROM comment WHERE root_id=#{root}")
    ThreadStats threadStats(UUID root);
    @Select(FIELDS+" WHERE c.id=#{id}")
    Comment find(UUID id);
    @Select("SELECT id,author_id,parent_id,root_id,deleted_at,moderation_state,thread_state FROM comment WHERE id=#{id} FOR UPDATE")
    Locked lock(UUID id);
    record Locked(UUID id,UUID authorId,UUID parentId,UUID rootId,OffsetDateTime deletedAt,String moderationState,String threadState) {}
    @Select("""
        WITH RECURSIVE chain(id,parent_id,depth) AS (
            SELECT c.id,c.parent_id,0 FROM comment c WHERE c.id=#{id}
            UNION ALL
            SELECT p.id,p.parent_id,chain.depth+1
            FROM comment p JOIN chain ON p.id=chain.parent_id
            WHERE chain.depth < #{limit}
        )
        SELECT c.id, c.author_id AS user_id, u.display_name AS user_name, c.parent_id,c.root_id,
               pu.display_name AS parent_user_name,
               CASE WHEN c.moderation_state='hidden' THEN NULL ELSE c.content END AS content,
               c.created_at,c.deleted_at,c.thread_state,c.updated_at,c.edit_deadline,c.revision,
               COALESCE((SELECT true FROM comment ch WHERE ch.parent_id=c.id ORDER BY ch.created_at,ch.id LIMIT 1),false) AS has_children,
               (SELECT count(*) FROM comment ch WHERE ch.parent_id=c.id) AS reply_count,
               (SELECT max(ch.created_at) FROM comment ch WHERE ch.parent_id=c.id) AS last_reply_at
        FROM chain JOIN comment c ON c.id=chain.id
        JOIN board_user u ON u.id=c.author_id
        LEFT JOIN comment p ON p.id=c.parent_id LEFT JOIN board_user pu ON pu.id=p.author_id
        WHERE chain.depth > 0
        ORDER BY chain.depth DESC
        """)
    List<Comment> ancestors(@Param("id") UUID id,@Param("limit") int limit);
    @Select("""
        WITH RECURSIVE chain(id,parent_id,depth) AS (
            SELECT c.id,c.parent_id,0 FROM comment c WHERE c.id=#{start}
            UNION ALL
            SELECT p.id,p.parent_id,chain.depth+1
            FROM comment p JOIN chain ON p.id=chain.parent_id
            WHERE chain.depth < #{limit}
        )
        SELECT c.id, c.author_id AS user_id, u.display_name AS user_name, c.parent_id,c.root_id,
               pu.display_name AS parent_user_name,
               CASE WHEN c.moderation_state='hidden' THEN NULL ELSE c.content END AS content,
               c.created_at,c.deleted_at,c.thread_state,c.updated_at,c.edit_deadline,c.revision,
               COALESCE((SELECT true FROM comment ch WHERE ch.parent_id=c.id ORDER BY ch.created_at,ch.id LIMIT 1),false) AS has_children,
               (SELECT count(*) FROM comment ch WHERE ch.parent_id=c.id) AS reply_count,
               (SELECT max(ch.created_at) FROM comment ch WHERE ch.parent_id=c.id) AS last_reply_at
        FROM chain JOIN comment c ON c.id=chain.id
        JOIN board_user u ON u.id=c.author_id
        LEFT JOIN comment p ON p.id=c.parent_id LEFT JOIN board_user pu ON pu.id=p.author_id
        ORDER BY chain.depth DESC
        """)
    List<Comment> ancestorPage(@Param("start") UUID start,@Param("limit") int limit);
    @Select("""
        WITH RECURSIVE chain(id,parent_id) AS (
            SELECT id,parent_id FROM comment WHERE id=#{target}
            UNION ALL
            SELECT p.id,p.parent_id FROM comment p JOIN chain ON p.id=chain.parent_id
        )
        SELECT EXISTS(SELECT 1 FROM chain WHERE id=#{candidate})
        """)
    boolean isAncestor(@Param("target") UUID target,@Param("candidate") UUID candidate);
    @Select("SELECT thread_state FROM comment WHERE id=#{id} AND parent_id IS NULL")
    String threadState(UUID id);
    @Select({"<script>", FIELDS,
        " WHERE <choose><when test='parent == null'>c.parent_id IS NULL</when><otherwise>c.parent_id=#{parent}</otherwise></choose>",
        "<if test='time != null'> AND (c.created_at,c.id) <choose><when test='newest'>&lt;</when><otherwise>&gt;</otherwise></choose> (#{time},#{after})</if>",
        " ORDER BY c.created_at <if test='newest'>DESC</if>,c.id <if test='newest'>DESC</if> LIMIT #{limit}", "</script>"})
    List<Comment> page(@Param("parent") UUID parent,@Param("newest") boolean newest,@Param("time") OffsetDateTime time,
                       @Param("after") UUID after,@Param("limit") int limit);
    @Select({"<script>", FIELDS,
        " WHERE c.parent_id IS NOT NULL AND c.deleted_at IS NULL AND c.moderation_state='visible'",
        "<if test='time != null'> AND (c.created_at,c.id) &lt; (#{time},#{after})</if>",
        " ORDER BY c.created_at DESC,c.id DESC LIMIT #{limit}", "</script>"})
    List<Comment> recent(@Param("time") OffsetDateTime time,@Param("after") UUID after,@Param("limit") int limit);
    @Select({"<script>", FIELDS,
        " WHERE c.deleted_at IS NULL AND c.moderation_state='visible' AND c.content ILIKE CONCAT('%',#{query},'%')",
        "<if test='time != null'> AND (c.created_at,c.id) &lt; (#{time},#{after})</if>",
        " ORDER BY c.created_at DESC,c.id DESC LIMIT #{limit}", "</script>"})
    List<Comment> search(@Param("query") String query,@Param("time") OffsetDateTime time,
                         @Param("after") UUID after,@Param("limit") int limit);
    @Insert("INSERT INTO comment(id,author_id,parent_id,root_id,content) VALUES(#{id},#{author},#{parent},#{root},#{content})")
    void insert(@Param("id") UUID id,@Param("author") UUID author,@Param("parent") UUID parent,
                @Param("root") UUID root,@Param("content") String content);
    @Update("UPDATE comment SET content=NULL,deleted_at=clock_timestamp() WHERE id=#{id} AND deleted_at IS NULL")
    void delete(UUID id);
    @Update("""
        UPDATE comment SET content=#{content},updated_at=clock_timestamp(),revision=revision+1
        WHERE id=#{id} AND revision=#{revision} AND deleted_at IS NULL
        AND edit_deadline>clock_timestamp()
        """)
    int updateContent(@Param("id") UUID id,@Param("content") String content,@Param("revision") int revision);
    @Insert("INSERT INTO comment_revision(id,comment_id,editor_id,revision,content) VALUES(#{id},#{comment},#{editor},#{revision},#{content})")
    void insertRevision(@Param("id") UUID id,@Param("comment") UUID comment,@Param("editor") UUID editor,
                        @Param("revision") int revision,@Param("content") String content);
    @Select("SELECT id,comment_id,editor_id,revision,content,created_at FROM comment_revision WHERE comment_id=#{comment} ORDER BY revision DESC,id DESC")
    List<CommentRevision> revisions(UUID comment);
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    String lockRequest(String key);
    record Request(String payloadHash,UUID commentId) {}
    @Select("SELECT payload_hash,comment_id FROM creation_request WHERE user_id=#{user} AND request_id=#{request}")
    Request request(@Param("user") UUID user,@Param("request") UUID request);
    @Insert("INSERT INTO creation_request(user_id,request_id,payload_hash,comment_id) VALUES(#{user},#{request},#{hash},#{comment})")
    void recordRequest(@Param("user") UUID user,@Param("request") UUID request,@Param("hash") String hash,@Param("comment") UUID comment);
}
