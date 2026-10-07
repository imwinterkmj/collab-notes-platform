package com.collabnotes.platform.reminder;

import com.collabnotes.platform.auth.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications = notifications; }
    @GetMapping
    public NotificationService.Page list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return unreadOnly ? notifications.listUnread(user.getId(), page, size) : notifications.list(user.getId(), page, size);
    }
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable @Positive long id,
                                    @AuthenticationPrincipal AuthenticatedUser user) {
        notifications.markRead(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
