package dev.messageboard.comment;

import dev.messageboard.auth.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class CommentController {
    private final CommentService service;
    public CommentController(CommentService service) { this.service=service; }
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status","UP"); }
    @GetMapping("/comments") public CommentService.Page roots(@RequestParam(defaultValue="newest") String sort,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) { return service.page(null,sort,limit,cursor); }
    @GetMapping("/comments/{id}/replies") public CommentService.Page replies(@PathVariable UUID id,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) { return service.page(id,"oldest",limit,cursor); }
    @GetMapping("/comments/{id}") public Comment get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping("/comments/{id}/context") public CommentService.Context context(@PathVariable UUID id,
            @RequestParam(defaultValue="8") int ancestorLimit,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) String ancestorCursor) {
        return service.context(id,ancestorLimit,limit,cursor,ancestorCursor);
    }
    @GetMapping("/comments/recent-replies") public CommentService.Page recentReplies(
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return service.recentReplies(limit,cursor);
    }
    @GetMapping("/comments/search") public CommentService.Page search(@RequestParam String q,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return service.search(q,limit,cursor);
    }
    @GetMapping("/comments/{id}/stats") public CommentService.ThreadStats stats(@PathVariable UUID id) { return service.stats(id); }
    @PostMapping("/comments") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Comment create(@AuthenticationPrincipal User.View user,@RequestBody CommentService.Create data) { return service.create(user.id(),data); }
    @PutMapping("/comments/{id}") public Comment edit(@AuthenticationPrincipal User.View user,@PathVariable UUID id,
            @RequestBody CommentService.Edit data) { return service.edit(user.id(),id,data); }
    @GetMapping("/comments/{id}/revisions") public List<CommentRevision> revisions(
            @AuthenticationPrincipal User.View user,@PathVariable UUID id) { return service.revisions(user.id(),id); }
    @DeleteMapping("/comments/{id}") public Comment delete(@AuthenticationPrincipal User.View user,@PathVariable UUID id) { return service.delete(user.id(),id); }
}
