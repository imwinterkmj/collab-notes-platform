package com.collabnotes.platform.reminder;

import com.collabnotes.platform.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notes/{id}/reminder")
public class ReminderController {
    private final ReminderService reminders;
    public ReminderController(ReminderService reminders) { this.reminders = reminders; }
    @GetMapping
    public ReminderResponse get(@PathVariable @Positive long id,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return reminders.get(id, user.getId());
    }
    @PutMapping
    public ReminderResponse set(@PathVariable @Positive long id,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               @Valid @RequestBody SetReminderRequest request) {
        return reminders.set(id, user.getId(), request);
    }
    @DeleteMapping
    public ResponseEntity<Void> cancel(@PathVariable @Positive long id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        reminders.cancel(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
