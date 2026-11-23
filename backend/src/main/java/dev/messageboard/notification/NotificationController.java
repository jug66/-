package dev.messageboard.notification;

import dev.messageboard.auth.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping public NotificationService.Page page(@AuthenticationPrincipal User.View user,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return service.page(user.id(),limit,cursor);
    }
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal User.View user,@PathVariable UUID id) { service.read(user.id(),id); }
}
