package com.collabnotes.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.async.DeferredResult;

class NoteChangeBusTests {
    private String cursor(NoteChangeBus bus, long user) {
        return ((NoteChangeBus.ChangeReply) ((ResponseEntity<?>) bus.watch(user, null, () -> true).getResult()).getBody()).cursor();
    }
    @Test void changeWakesOnlyOwnerAndWrongCursorResyncsWithoutContents() {
        var bus = new NoteChangeBus();
        var first = bus.watch(1, cursor(bus, 1), () -> true);
        var other = bus.watch(2, cursor(bus, 2), () -> true);
        assertThat(first.hasResult()).isFalse();
        bus.afterCommit(1);
        assertThat(first.hasResult()).isTrue();
        assertThat(other.hasResult()).isFalse();
        var response = (ResponseEntity<?>) first.getResult();
        assertThat(((NoteChangeBus.ChangeReply) response.getBody()).changed()).isTrue();
        assertThat(bus.watch(1, "old-process-cursor", () -> true).hasResult()).isTrue();
        bus.close();
        assertThat(bus.pendingCount()).isZero();
    }
    @Test void transactionCommitCoalescesAndRollbackDoesNotNotify() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(database));
        var bus = new NoteChangeBus();
        try {
            var waiting = bus.watch(7, cursor(bus, 7), () -> true);
            tx.execute(status -> {
                bus.afterCommit(7);
                bus.afterCommit(7);
                assertThat(waiting.hasResult()).isFalse();
                return null;
            });
            assertThat(waiting.hasResult()).isTrue();
            String committed = ((NoteChangeBus.ChangeReply) ((ResponseEntity<?>) waiting.getResult()).getBody()).cursor();
            var next = bus.watch(7, committed, () -> true);
            assertThat(next.hasResult()).isFalse(); // 同事务只有一个版本。
            tx.execute(status -> { bus.afterCommit(7); status.setRollbackOnly(); return null; });
            assertThat(next.hasResult()).isFalse();
        } finally { bus.close(); database.shutdown(); }
    }
    @Test void logoutWhileWaitingNeverReceivesChangeMarker() {
        var bus = new NoteChangeBus();
        var valid = new AtomicBoolean(true);
        var waiting = bus.watch(7, cursor(bus, 7), valid::get);
        valid.set(false);
        bus.afterCommit(7);
        assertThat(((ResponseEntity<?>) waiting.getResult()).getStatusCode().value()).isEqualTo(401);
        assertThat(bus.pendingCount()).isZero();
        bus.close();
    }
    @Test void perOwnerAndGlobalPendingLimitsDoNotGrowUnbounded() {
        var bus = new NoteChangeBus();
        String first = cursor(bus, 1);
        for (int i = 0; i < NoteChangeBus.MAX_PER_OWNER; i++) { bus.watch(1, first, () -> true); }
        assertThat(((ResponseEntity<?>) bus.watch(1, first, () -> true).getResult()).getStatusCode().value()).isEqualTo(429);
        for (long user = 2; bus.pendingCount() < NoteChangeBus.MAX_PENDING; user++) {
            bus.watch(user, cursor(bus, user), () -> true);
        }
        assertThat(((ResponseEntity<?>) bus.watch(9999, cursor(bus, 9999), () -> true).getResult()).getStatusCode().value()).isEqualTo(429);
        assertThat(bus.pendingCount()).isEqualTo(NoteChangeBus.MAX_PENDING);
        bus.close();
        assertThat(bus.pendingCount()).isZero();
    }
    @Test void idleOwnerCacheHasHardBoundAndEvictionForcesResync() {
        var bus = new NoteChangeBus();
        String old = cursor(bus, 1);
        for (int user = 2; user < NoteChangeBus.MAX_OWNERS + 200; user++) { cursor(bus, user); }
        assertThat(bus.ownerCount()).isEqualTo(NoteChangeBus.MAX_OWNERS);
        assertThat(bus.watch(1, old, () -> true).hasResult()).isTrue();
        bus.close();
    }
}
