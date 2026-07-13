package com.enviro.brain.scheduler;

import com.enviro.brain.config.ScenarioConfigs;
import com.enviro.brain.service.CameraConfigService;
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
    private final CameraConfigService cameraConfigService;
    private final ScenarioConfigs scenarioConfigs;

    @Scheduled(cron = "${enviro.inspection.cron:0 0 9,15 * * ?}")
    public void scheduledInspection() {
        log.info("[Scheduler] 定时巡检触发: {}", LocalDateTime.now());
        for (String scenario : scenarioConfigs.getScenarios().keySet()) {
            try {
                if (cameraConfigService.countByScenario(scenario) == 0) {
                    log.info("[Scheduler] 场景 {} 无摄像头，跳过", scenario);
                    continue;
                }
                Long inspectId = inspectionService.executeInspection("auto", scenario);
                log.info("[Scheduler] 场景 {} 巡检完成, inspectId={}", scenario, inspectId);
            } catch (Exception e) {
                log.error("[Scheduler] 场景 {} 巡检异常", scenario, e);
            }
        }
    }
}
