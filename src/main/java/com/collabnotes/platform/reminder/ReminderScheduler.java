package com.collabnotes.platform.reminder;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.reminders.scheduler-enabled", havingValue = "true")
public class ReminderScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ReminderScheduler.class);
    private final ReminderDispatcher dispatcher;
    private final int batchSize;
    public ReminderScheduler(ReminderDispatcher dispatcher, @Value("${app.reminders.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) { throw new IllegalArgumentException("提醒批量须为 1～1000"); }
        this.dispatcher = dispatcher;
        this.batchSize = batchSize;
    }
    @Scheduled(fixedDelayString = "${app.reminders.poll-delay-ms:5000}", initialDelay = 5000)
    public void poll() {
        try { dispatcher.dispatchDue(Instant.now(), batchSize); }
        catch (RuntimeException exception) {
            LOG.error("提醒扫描失败，错误类型：{}", exception.getClass().getSimpleName());
        }
    }
}
