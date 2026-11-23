package dev.messageboard.comment;

import dev.messageboard.auth.UserMapper;
import dev.messageboard.common.ApiException;
import dev.messageboard.common.Checks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ModerationService {
    private final ModerationMapper mapper;
    private final CommentMapper comments;
    private final UserMapper users;
    private final Set<UUID> admins;
    public ModerationService(ModerationMapper mapper,CommentMapper comments,UserMapper users,@Value("${board.admin-user-ids:}") String configured) {
        this.mapper=mapper; this.comments=comments; this.users=users;
        this.admins=new HashSet<>();
        for(String value:configured.split(",")) try { if(!value.isBlank()) admins.add(UUID.fromString(value.trim())); } catch(IllegalArgumentException ignored) {}
    }
    public void requireAdmin(UUID actor){ if(!admins.contains(actor)) throw new ApiException(403,"没有管理员权限"); }
    public void requireActive(UUID user){ if(Boolean.TRUE.equals(mapper.blocked(user))) throw new ApiException(403,"账号已被限制发言"); }
    public void ensureThreadOpen(UUID root){ String state=comments.threadState(root); if("locked".equals(state)||"archived".equals(state)) throw new ApiException(409,"这条讨论已关闭回复"); }
    @Transactional(timeout=10)
    public void report(UUID reporter,UUID comment,String reason){
        if(comments.find(comment)==null) throw new ApiException(404,"这条留言不存在");
        Checks.text(reason,1,500,"举报原因");
        try { mapper.report(UUID.randomUUID(),comment,reporter,reason.strip()); }
        catch(org.springframework.dao.DuplicateKeyException ex){ throw new ApiException(409,"你已经举报过这条留言"); }
    }
    @Transactional(timeout=10)
    public void visibility(UUID actor,UUID id,boolean hidden,String reason){
        requireAdmin(actor); if(comments.lock(id)==null) throw new ApiException(404,"这条留言不存在");
        if(reason!=null) Checks.text(reason,0,240,"治理原因");
        mapper.setVisibility(id,hidden?"hidden":"visible",actor,reason); mapper.audit(UUID.randomUUID(),actor,hidden?"hide_comment":"restore_comment","comment",id,reason);
    }
    @Transactional(timeout=10)
    public void threadState(UUID actor,UUID id,String state,String reason){
        requireAdmin(actor); if(!Set.of("open","resolved","locked","archived").contains(state)) throw ApiException.bad("线程状态不正确");
        if(reason!=null) Checks.text(reason,0,500,"治理原因");
        var row=comments.lock(id); if(row==null) throw new ApiException(404,"这条留言不存在");
        UUID root=row.rootId(); if(comments.lock(root)==null) throw new ApiException(404,"讨论不存在");
        if(mapper.setThreadState(root,state)!=1) throw ApiException.bad("只能修改根留言的线程状态");
        mapper.audit(UUID.randomUUID(),actor,"thread_"+state,"thread",root,reason);
    }
    @Transactional(timeout=10)
    public void blocked(UUID actor,UUID id,boolean blocked,String reason){
        requireAdmin(actor); if(users.findById(id)==null) throw new ApiException(404,"用户不存在");
        if(reason!=null) Checks.text(reason,0,240,"治理原因");
        mapper.setBlocked(id,blocked,reason); mapper.audit(UUID.randomUUID(),actor,blocked?"block_user":"unblock_user","user",id,reason);
    }
    public List<ModerationMapper.Report> reports(UUID actor){requireAdmin(actor);return mapper.openReports();}
    public List<ModerationMapper.Audit> audits(UUID actor){requireAdmin(actor);return mapper.audits();}
    public boolean isAdmin(UUID actor) { return admins.contains(actor); }
    @Transactional(timeout=10)
    public void resolveReport(UUID actor, UUID id, String status, String reason) {
        requireAdmin(actor);
        if (!Set.of("resolved", "dismissed").contains(status)) throw ApiException.bad("举报状态不正确");
        if (reason != null) Checks.text(reason, 0, 500, "处理原因");
        if (mapper.resolveReport(id, status, actor) != 1) throw new ApiException(404, "举报不存在或已经处理");
        mapper.audit(UUID.randomUUID(), actor, "report_" + status, "report", id, reason);
    }
}
