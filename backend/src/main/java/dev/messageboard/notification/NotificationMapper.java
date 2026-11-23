package dev.messageboard.notification;

import dev.messageboard.comment.CursorCodec;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.*;

@Mapper
public interface NotificationMapper {
    String FIELDS = """
        SELECT n.id,n.recipient_id,n.actor_id,u.display_name AS actor_name,n.comment_id,n.type,
               c.content AS preview,n.created_at,n.read_at
        FROM notification n
        JOIN board_user u ON u.id=n.actor_id
        JOIN comment c ON c.id=n.comment_id
        """;
    @Insert("INSERT INTO notification(id,recipient_id,actor_id,comment_id,type,dedupe_key) VALUES(#{id},#{recipient},#{actor},#{comment},#{type},#{dedupe}) ON CONFLICT (recipient_id,dedupe_key) DO NOTHING")
    int insert(@Param("id") UUID id,@Param("recipient") UUID recipient,@Param("actor") UUID actor,
               @Param("comment") UUID comment,@Param("type") String type,@Param("dedupe") String dedupe);
    @Select({"<script>", FIELDS,
        " WHERE n.recipient_id=#{recipient} AND c.deleted_at IS NULL AND c.moderation_state='visible'",
        "<if test='time != null'> AND (n.created_at,n.id) &lt; (#{time},#{after})</if>",
        " ORDER BY n.created_at DESC,n.id DESC LIMIT #{limit}", "</script>"})
    List<Notification> page(@Param("recipient") UUID recipient,@Param("time") OffsetDateTime time,
                            @Param("after") UUID after,@Param("limit") int limit);
    @Update("UPDATE notification SET read_at=COALESCE(read_at,clock_timestamp()) WHERE id=#{id} AND recipient_id=#{recipient}")
    int markRead(@Param("id") UUID id,@Param("recipient") UUID recipient);
}
