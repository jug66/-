package dev.messageboard.comment;

import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.*;

@Mapper
public interface ModerationMapper {
    record Report(UUID id,UUID commentId,UUID reporterId,String reason,String status,OffsetDateTime createdAt) {}
    record Audit(UUID id,UUID actorId,String action,String targetType,UUID targetId,String reason,OffsetDateTime createdAt) {}
    @Insert("INSERT INTO comment_report(id,comment_id,reporter_id,reason) VALUES(#{id},#{comment},#{reporter},#{reason})")
    void report(@Param("id") UUID id,@Param("comment") UUID comment,@Param("reporter") UUID reporter,@Param("reason") String reason);
    @Select("SELECT id,comment_id,reporter_id,reason,status,created_at FROM comment_report WHERE status='open' ORDER BY created_at,id LIMIT 200")
    List<Report> openReports();
    @Update("UPDATE comment SET moderation_state=#{state},hidden_at=CASE WHEN #{state}='hidden' THEN clock_timestamp() ELSE NULL END,hidden_by=CASE WHEN #{state}='hidden' THEN #{actor} ELSE NULL END,hidden_reason=CASE WHEN #{state}='hidden' THEN #{reason} ELSE NULL END WHERE id=#{id}")
    int setVisibility(@Param("id") UUID id,@Param("state") String state,@Param("actor") UUID actor,@Param("reason") String reason);
    @Update("UPDATE comment SET thread_state=#{state} WHERE id=#{id} AND parent_id IS NULL")
    int setThreadState(@Param("id") UUID id,@Param("state") String state);
    @Update("UPDATE board_user SET blocked_at=CASE WHEN #{blocked} THEN clock_timestamp() ELSE NULL END,blocked_reason=CASE WHEN #{blocked} THEN #{reason} ELSE NULL END WHERE id=#{id}")
    int setBlocked(@Param("id") UUID id,@Param("blocked") boolean blocked,@Param("reason") String reason);
    @Select("SELECT blocked_at IS NOT NULL FROM board_user WHERE id=#{id}")
    Boolean blocked(UUID id);
    @Insert("INSERT INTO moderation_audit(id,actor_id,action,target_type,target_id,reason) VALUES(#{id},#{actor},#{action},#{type},#{target},#{reason})")
    void audit(@Param("id") UUID id,@Param("actor") UUID actor,@Param("action") String action,@Param("type") String type,@Param("target") UUID target,@Param("reason") String reason);
    @Select("SELECT id,actor_id,action,target_type,target_id,reason,created_at FROM moderation_audit ORDER BY created_at DESC,id DESC LIMIT 200")
    List<Audit> audits();
    @Update("UPDATE comment_report SET status=#{status},resolved_at=clock_timestamp(),resolved_by=#{actor} WHERE id=#{id} AND status='open'")
    int resolveReport(@Param("id") UUID id,@Param("status") String status,@Param("actor") UUID actor);
}
