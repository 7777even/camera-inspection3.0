package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.mapper.CameraResultMapper;
import com.enviro.brain.mapper.InspectionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class InspectionService {

    private final CameraConfigService cameraConfigService;
    private final CaptureService captureService;
    private final SyncVersionService syncVersionService;
    private final InspectionRecordMapper inspectionRecordMapper;
    private final CameraResultMapper cameraResultMapper;
    private final LedgerService ledgerService;
    private final FeishuNotifyService feishuNotifyService;
    private final QueqiaoNotifyService queqiaoNotifyService;

    @Value("${enviro.inspection.concurrency:12}")
    private int concurrency;

    /**
     * 执行一次完整巡检。
     * @param triggerType "auto" | "manual"
     * @return inspectId
     */
    @Transactional
    public Long executeInspection(String triggerType) {
        LocalDateTime startTime = LocalDateTime.now();
        log.info("[Inspection] 开始执行巡检，触发类型：{}", triggerType);

        // ① 读取启用的摄像头清单
        List<CameraConfig> cameras = cameraConfigService.findActive(1, 10000);
        log.info("[Inspection] 共读取 {} 路摄像头", cameras.size());

        // ② 获取全局同步版本号
        long syncVersion = syncVersionService.nextVersion();

        // ③ 创建巡检记录（running）
        InspectionRecord record = new InspectionRecord();
        record.setBatchId(triggerType + "-" + startTime.toLocalDate() + "-"
                + startTime.toLocalTime().toString().replace(":", "").substring(0, 4));
        record.setInspectionDate(LocalDate.now());
        record.setTotalCameras(cameras.size());
        record.setOnlineCount(0);
        record.setOfflineCount(0);
        record.setAbnormalCount(0);
        record.setStatus("RUNNING");
        record.setSyncVersion(syncVersion);
        record.setCreatedAt(startTime);
        inspectionRecordMapper.insert(record);
        Long inspectId = record.getId();

        // ④ 并发截图
        List<CameraResult> results = new ArrayList<>();
        if (!cameras.isEmpty()) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    concurrency, concurrency, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            List<Future<CameraCaptureResult>> futures = new ArrayList<>();
            for (CameraConfig cam : cameras) {
                futures.add(pool.submit(() -> {
                    try {
                        return captureService.capture(cam);
                    } catch (Exception e) {
                        log.warn("[Inspection] {} 截图失败: {}", cam.getCameraCode(), e.getMessage());
                        CameraCaptureResult error = new CameraCaptureResult();
                        error.setStatus("error");
                        error.setErrorMsg(e.getMessage());
                        return error;
                    }
                }));
            }

            for (int i = 0; i < futures.size(); i++) {
                CameraConfig cam = cameras.get(i);
                try {
                    CameraCaptureResult captureResult = futures.get(i).get(60, TimeUnit.SECONDS);
                    CameraResult entity = buildCameraResult(cam, captureResult, inspectId, syncVersion);
                    results.add(entity);
                } catch (TimeoutException e) {
                    log.warn("[Inspection] {} 截图超时", cam.getCameraCode());
                    CameraResult timeout = buildErrorResult(cam, inspectId, "截图超时(60s)", syncVersion);
                    results.add(timeout);
                } catch (Exception e) {
                    log.warn("[Inspection] {} 截图异常: {}", cam.getCameraCode(), e.getMessage());
                    CameraResult error = buildErrorResult(cam, inspectId, e.getMessage(), syncVersion);
                    results.add(error);
                }
            }
            pool.shutdownNow();
        }

        // ⑤ 批量写 camera_results
        if (!results.isEmpty()) {
            cameraResultMapper.batchInsert(results);
        }

        // ⑥ 汇总统计
        int online = (int) results.stream().filter(r -> "online".equals(r.getStatus())).count();
        int offline = (int) results.stream().filter(r -> "offline".equals(r.getStatus())).count();
        int abnormal = (int) results.stream().filter(r -> !"online".equals(r.getStatus()) && !"offline".equals(r.getStatus())).count();

        record.setOnlineCount(online);
        record.setOfflineCount(offline);
        record.setAbnormalCount(abnormal);
        record.setStatus("COMPLETED");
        inspectionRecordMapper.updateById(record);

        // ⑦ 生成台账
        List<CameraResult> ledgerTargets = results.stream()
                .filter(r -> ledgerService.shouldRegisterToLedger(r))
                .collect(Collectors.toList());
        String docxPath = null;
        try {
            docxPath = ledgerService.generateAndSave(inspectId, ledgerTargets, syncVersion);
        } catch (Exception e) {
            log.error("[Inspection] 台账生成失败: {}", e.getMessage());
        }

        // ⑧ 飞书通知
        try {
            feishuNotifyService.sendInspectionReport(record, results);
        } catch (Exception e) {
            log.error("[Inspection] 飞书通知异常: {}", e.getMessage());
        }

        // ⑨ 鹊桥回调（可选）
        try {
            queqiaoNotifyService.notifyNewData(syncVersion);
        } catch (Exception e) {
            log.error("[Inspection] 鹊桥回调异常: {}", e.getMessage());
        }

        log.info("[Inspection] 巡检完成: 在线{} 离线{} 异常{}", online, offline, abnormal);
        return inspectId;
    }

    private CameraResult buildCameraResult(CameraConfig config, CameraCaptureResult capture, Long inspectId, long syncVersion) {
        CameraResult entity = new CameraResult();
        entity.setRecordId(inspectId);
        entity.setCameraCode(config.getCameraCode());
        entity.setCameraName(config.getCameraName());
        entity.setStatus(capture.getStatus());
        entity.setQualityScore(capture.getQualityScore() != null ? BigDecimal.valueOf(capture.getQualityScore()) : null);
        entity.setScreenshotPath(capture.getScreenshotPath());
        entity.setErrorMessage(capture.getErrorMsg());
        entity.setSyncVersion(syncVersion);
        return entity;
    }

    private CameraResult buildErrorResult(CameraConfig config, Long inspectId, String errorMsg, long syncVersion) {
        CameraResult entity = new CameraResult();
        entity.setRecordId(inspectId);
        entity.setCameraCode(config.getCameraCode());
        entity.setCameraName(config.getCameraName());
        entity.setStatus("error");
        entity.setErrorMessage(errorMsg);
        entity.setSyncVersion(syncVersion);
        return entity;
    }
}
