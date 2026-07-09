package com.enviro.brain.scheduler;

import com.enviro.brain.service.InspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class InspectionScheduler {

    private final InspectionService inspectionService;

    @Scheduled(cron = "${enviro.inspection.cron:0 0 9,15 * * ?}")
    public void scheduledInspection() {
        log.info("[Scheduler] 定时巡检触发: {}", LocalDateTime.now());
        try {
            Long inspectId = inspectionService.executeInspection("auto");
            log.info("[Scheduler] 巡检完成, inspectId={}", inspectId);
        } catch (Exception e) {
            log.error("[Scheduler] 巡检异常", e);
        }
    }
}
