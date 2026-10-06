package com.collabnotes.platform.note;

import com.collabnotes.platform.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
