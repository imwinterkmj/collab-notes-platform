package com.collabnotes.platform.sync;

import com.collabnotes.platform.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
public class NoteChangeController {
    private final NoteChangeBus changes;
    public NoteChangeController(NoteChangeBus changes) { this.changes = changes; }

    @GetMapping("/api/notes/changes")
    public DeferredResult<ResponseEntity<?>> changes(@AuthenticationPrincipal AuthenticatedUser user,
                    @RequestParam(required = false) @Size(max = 64) String cursor, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return changes.watch(user.getId(), cursor, () -> stillOwned(session, user.getId()));
    }
    // 请求开始时认证，返回时再核验同一个会话，退出/轮换账号后不发迟到提示。
    private boolean stillOwned(HttpSession session, long userId) {
        if (session == null) { return false; }
        try {
            var value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(value instanceof SecurityContext context) || context.getAuthentication() == null
                    || !context.getAuthentication().isAuthenticated()) { return false; }
            return context.getAuthentication().getPrincipal() instanceof AuthenticatedUser user
                    && user.getId() == userId;
        } catch (IllegalStateException invalidated) { return false; }
    }
}
