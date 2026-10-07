package com.collabnotes.platform.note;

import com.collabnotes.platform.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
public class NoteController {
    private final NoteService service;

    public NoteController(NoteService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<NoteResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody CreateNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.getId(), request));
    }

    @GetMapping("/{id}")
    public NoteResponse detail(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable @Positive long id) {
        return service.detail(id, user.getId());
    }

    @GetMapping
    public NotePageResponse list(@AuthenticationPrincipal AuthenticatedUser user,
                                @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(user.getId(), page, size);
    }

    @PutMapping("/{id}")
    public NoteResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable @Positive long id,
                               @Valid @RequestBody UpdateNoteRequest request) {
        return service.update(id, user.getId(), request);
    }

    @GetMapping("/trash")
    public TrashPageResponse trash(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.trash(user.getId());
    }

    @PostMapping("/trash/{id}/restore")
    public NoteResponse restore(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Positive long id) {
        return service.restore(id, user.getId());
    }

    @PatchMapping("/{id}/completion")
    public NoteResponse setCompletion(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable @Positive long id,
                                      @Valid @RequestBody UpdateNoteCompletionRequest request) {
        return service.setCompletion(id, user.getId(), request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable @Positive long id) {
        service.delete(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
