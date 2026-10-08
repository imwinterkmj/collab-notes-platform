package com.collabnotes.platform.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import jakarta.annotation.PreDestroy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.async.DeferredResult;

/** 单实例、仅失效提示的有界长轮询；数据库仍是事实来源，不保存正文或事件历史。 */
@Component
public class NoteChangeBus {
    static final int MAX_OWNERS = 512;
    static final int MAX_PER_OWNER = 4;
    static final int MAX_PENDING = 256;
    static final long WAIT_MILLIS = 8000;
    private static final long IDLE_MILLIS = 300_000;
    private final String instance = UUID.randomUUID().toString().replace("-", "");
    private final Map<Long, Owner> owners = new HashMap<>();
    private long sequence;
    private int pending;
    private boolean closed;

    public record ChangeReply(String cursor, boolean changed) {}
    public record SyncError(String code) {}
    private final class Owner {
        String cursor = nextCursor();
        long touched = System.currentTimeMillis();
        final List<Waiting> waiting = new ArrayList<>();
    }
    private record Waiting(DeferredResult<ResponseEntity<?>> result, BooleanSupplier allowed) {}
    private String nextCursor() { return instance + "-" + (++sequence); }

    public synchronized DeferredResult<ResponseEntity<?>> watch(long userId, String cursor,
                                                                  BooleanSupplier allowed) {
        if (closed) { return immediate(error(503, "SYNC_BUSY")); }
        long now = System.currentTimeMillis();
        owners.values().removeIf(owner -> owner.waiting.isEmpty() && now - owner.touched > IDLE_MILLIS);
        var owner = owners.get(userId);
        if (owner == null) {
            if (owners.size() >= MAX_OWNERS) {
                var oldest = owners.entrySet().stream().filter(entry -> entry.getValue().waiting.isEmpty())
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue().touched));
                oldest.ifPresent(entry -> owners.remove(entry.getKey()));
            }
            if (owners.size() >= MAX_OWNERS) { return immediate(error(503, "SYNC_BUSY")); }
            owner = new Owner();
            owners.put(userId, owner);
        }
        owner.touched = now;
        if (!owner.cursor.equals(cursor)) { return immediate(reply(owner, true, allowed)); }
        if (owner.waiting.size() >= MAX_PER_OWNER || pending >= MAX_PENDING) {
            return immediate(error(429, "SYNC_BUSY"));
        }
        var result = new DeferredResult<ResponseEntity<?>>(WAIT_MILLIS);
        var waiting = new Waiting(result, allowed);
        var selected = owner;
        owner.waiting.add(waiting);
        pending++;
        result.onTimeout(() -> finish(selected, waiting, false));
        result.onCompletion(() -> remove(selected, waiting));
        result.onError(ignored -> remove(selected, waiting));
        return result;
    }

    private DeferredResult<ResponseEntity<?>> immediate(ResponseEntity<?> response) {
        var result = new DeferredResult<ResponseEntity<?>>(WAIT_MILLIS);
        result.setResult(response);
        return result;
    }
    private ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).body(new SyncError(code));
    }
    private ResponseEntity<?> reply(Owner owner, boolean changed, BooleanSupplier allowed) {
        return allowed.getAsBoolean() ? ResponseEntity.ok(new ChangeReply(owner.cursor, changed))
                : error(401, "UNAUTHENTICATED");
    }
    private synchronized void remove(Owner owner, Waiting waiting) {
        if (owner.waiting.remove(waiting)) { pending--; }
    }
    private void finish(Owner owner, Waiting waiting, boolean changed) {
        ResponseEntity<?> response;
        synchronized (this) {
            if (!owner.waiting.remove(waiting)) { return; }
            pending--;
            response = reply(owner, changed, waiting.allowed());
        }
        // DeferredResult 唤醒容器，不在数据库事务里向设备写 socket。
        complete(waiting.result(), response);
    }
    private void complete(DeferredResult<ResponseEntity<?>> result, ResponseEntity<?> response) {
        try { result.setResult(response); }
        catch (RuntimeException ignored) {
            // 提交已完成；连接失败不能把成功写入伪装成失败。客户端重连会核对当前游标。
        }
    }
    private void publish(long userId) {
        Owner owner;
        List<Waiting> waiting;
        synchronized (this) {
            if (closed) { return; }
            owner = owners.get(userId);
            if (owner == null) { return; } // 下次首次连接会要求读取完整当前状态。
            owner.cursor = nextCursor();
            owner.touched = System.currentTimeMillis();
            waiting = List.copyOf(owner.waiting);
        }
        for (var item : waiting) { finish(owner, item, true); }
    }

    public void afterCommit(long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(userId); // 单语句自动提交的原创建接口。
            return;
        }
        for (var hook : TransactionSynchronizationManager.getSynchronizations()) {
            if (hook instanceof CommitSignal signal && signal.bus() == this) {
                signal.users.add(userId);
                return;
            }
        }
        var signal = new CommitSignal();
        signal.users.add(userId);
        TransactionSynchronizationManager.registerSynchronization(signal);
    }
    private final class CommitSignal implements TransactionSynchronization {
        final LinkedHashSet<Long> users = new LinkedHashSet<>();
        NoteChangeBus bus() { return NoteChangeBus.this; }
        @Override public void afterCommit() {
            for (long userId : users) { publish(userId); }
        }
    }
    @PreDestroy
    synchronized void close() {
        closed = true;
        for (var owner : owners.values()) {
            for (var item : List.copyOf(owner.waiting)) {
                remove(owner, item);
                complete(item.result(), error(503, "SYNC_BUSY"));
            }
        }
        owners.clear();
    }
    synchronized int pendingCount() { return pending; }
    synchronized int ownerCount() { return owners.size(); }
}
