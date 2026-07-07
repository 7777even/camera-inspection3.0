package com.queqiao.sync.scheduler;

import com.queqiao.sync.service.SyncOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时同步调度器：每 {@code queqiao.sync.cron}（默认每 30 分钟）触发一轮增量同步。
 * 异常被捕获并记日志，保证调度线程不被单次失败中断（设计 D6）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final SyncOrchestrationService syncOrchestrationService;

    @Scheduled(cron = "${queqiao.sync.cron}")
    public void scheduledSync() {
        log.info("[scheduler] 触发定时同步");
        try {
            syncOrchestrationService.syncOnce();
        } catch (Exception e) {
            log.error("[scheduler] 定时同步失败（不阻断后续调度）", e);
        }
    }
}
