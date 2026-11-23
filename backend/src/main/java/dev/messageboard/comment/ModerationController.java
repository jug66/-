package dev.messageboard.comment;

import dev.messageboard.auth.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ModerationController {
    private final ModerationService moderation;
    private final CommentService comments;
    public ModerationController(ModerationService moderation,CommentService comments){this.moderation=moderation;this.comments=comments;}
    @GetMapping("/moderation/me") public Map<String,Boolean> me(@AuthenticationPrincipal User.View user){return Map.of("admin",moderation.isAdmin(user.id()));}
    @PostMapping("/comments/{id}/reports") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@AuthenticationPrincipal User.View user,@PathVariable UUID id,@RequestBody Report body){moderation.report(user.id(),id,body.reason());}
    public record Report(String reason) {}
    @GetMapping("/moderation/reports") public List<ModerationMapper.Report> reports(@AuthenticationPrincipal User.View user){return moderation.reports(user.id());}
    @GetMapping("/moderation/audit") public List<ModerationMapper.Audit> audits(@AuthenticationPrincipal User.View user){return moderation.audits(user.id());}
    @PostMapping("/moderation/comments/{id}") public Comment visibility(@AuthenticationPrincipal User.View user,@PathVariable UUID id,@RequestBody Visibility body){moderation.visibility(user.id(),id,body.hidden(),body.reason());return comments.get(id);}
    public record Visibility(boolean hidden,String reason) {}
    @PostMapping("/moderation/threads/{id}") public Comment thread(@AuthenticationPrincipal User.View user,@PathVariable UUID id,@RequestBody ThreadState body){moderation.threadState(user.id(),id,body.state(),body.reason());return comments.get(id);}
    public record ThreadState(String state,String reason) {}
    @PostMapping("/moderation/users/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void user(@AuthenticationPrincipal User.View user,@PathVariable UUID id,@RequestBody Block body){moderation.blocked(user.id(),id,body.blocked(),body.reason());}
    public record Block(boolean blocked,String reason) {}
    @PostMapping("/moderation/reports/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@AuthenticationPrincipal User.View user,@PathVariable UUID id,@RequestBody Resolve body){moderation.resolveReport(user.id(),id,body.status(),body.reason());}
    public record Resolve(String status,String reason) {}
}
