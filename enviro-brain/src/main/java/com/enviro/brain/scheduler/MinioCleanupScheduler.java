package com.enviro.brain.scheduler;

import com.enviro.brain.service.MinioStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
public class MinioCleanupScheduler {

    private final MinioStorageService minioStorageService;

    public MinioCleanupScheduler(MinioStorageService minioStorageService) {
        this.minioStorageService = minioStorageService;
    }

    /** 应用启动后先跑一次 catch-up，避免错过调度窗口 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            int n = minioStorageService.cleanupExpiredObjects();
            log.info("[Minio] 启动清理完成: 删除 {} 张", n);
        } catch (Exception e) {
            log.warn("[Minio] 启动清理异常: {}", e.getMessage());
        }
    }

    /** 每日 02:00 清理；cron 可用 yml/env 覆盖，缺省 0 0 2 * * ? */
    @Scheduled(cron = "${enviro.minio.cleanup.cron:0 0 2 * * ?}")
    public void scheduledCleanup() {
        try {
            int n = minioStorageService.cleanupExpiredObjects();
            log.info("[Minio] 定时清理完成: 删除 {} 张", n);
        } catch (Exception e) {
            log.warn("[Minio] 定时清理异常: {}", e.getMessage());
        }
    }
}
