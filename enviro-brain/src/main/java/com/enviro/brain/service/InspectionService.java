package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.dto.InspectionContext;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.mapper.CameraResultMapper;
import com.enviro.brain.mapper.InspectionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final MinioStorageService minioStorageService;

    @Value("${enviro.inspection.concurrency:12}")
    private int concurrency;

    @Value("${enviro.inspection.capture-timeout-seconds:120}")
    private int captureTimeoutSeconds;

    /**
     * Phase 1: 创建巡检记录（RUNNING），返回上下文。
     * 同步 + @Transactional，由控制器或调度器调用。
     */
    @Transactional
    public InspectionContext prepareInspection(String triggerType) {
        LocalDateTime startTime = LocalDateTime.now();
        log.info("[Inspection] 准备巡检，触发类型：{}", triggerType);

        List<CameraConfig> cameras = cameraConfigService.findActive(1, 10000);
        log.info("[Inspection] 共读取 {} 路摄像头", cameras.size());

        long syncVersion = syncVersionService.nextVersion();

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

        InspectionContext ctx = new InspectionContext();
        ctx.setInspectId(record.getId());
        ctx.setSyncVersion(syncVersion);
        ctx.setCameras(cameras);
        ctx.setRecord(record);
        return ctx;
    }

    /**
     * Phase 2: 执行巡检主体（截图 + 写库 + 台账 + 通知 + 回调）。
     * 无事务注解 — async 路径各 DB 操作自动提交；sync 路径由外层 @Transactional 覆盖。
     */
    void runInspectionBody(InspectionContext ctx) {
        Long inspectId = ctx.getInspectId();
        long syncVersion = ctx.getSyncVersion();
        List<CameraConfig> cameras = ctx.getCameras();
        InspectionRecord record = ctx.getRecord();

        log.info("[Inspection] 开始执行巡检主体, inspectId={}", inspectId);

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
                    CameraCaptureResult captureResult = futures.get(i).get(captureTimeoutSeconds, TimeUnit.SECONDS);
                    CameraResult entity = buildCameraResult(cam, captureResult, inspectId, syncVersion);
                    results.add(entity);
                } catch (TimeoutException e) {
                    log.warn("[Inspection] {} 截图超时", cam.getCameraCode());
                    String fallbackPath = captureService.findScreenshot(cam.getCameraName());
                    if (fallbackPath != null) {
                        log.info("[Inspection] {} 截图文件已存在: {}，按在线处理", cam.getCameraCode(), fallbackPath);
                        CameraResult online = buildErrorResult(cam, inspectId, null, syncVersion);
                        online.setStatus("online");
                        online.setLocalScreenshotPath(fallbackPath);
                        online.setScreenshotPath(uploadToMinio(fallbackPath, cam.getCameraName()));
                        results.add(online);
                    } else {
                        CameraResult offline = buildErrorResult(cam, inspectId, "截图超时(" + captureTimeoutSeconds + "s)", syncVersion);
                        offline.setStatus("offline");
                        results.add(offline);
                    }
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
        Set<String> ledgerCodes = cameras.stream()
                .filter(c -> c.getLedgerEnabled() != null && c.getLedgerEnabled() == 1)
                .map(CameraConfig::getCameraCode)
                .collect(Collectors.toSet());
        List<CameraResult> ledgerTargets = results.stream()
                .filter(r -> ledgerCodes.contains(r.getCameraCode()))
                .collect(Collectors.toList());
        try {
            ledgerService.generateAndSave(inspectId, ledgerTargets, syncVersion);
        } catch (Exception e) {
            log.error("[Inspection] 台账生成失败: {}", e.getMessage());
        }

        // ⑧ 飞书通知
        try {
            feishuNotifyService.sendInspectionReport(record, results);
        } catch (Exception e) {
            log.error("[Inspection] 飞书通知异常: {}", e.getMessage());
        }

        // ⑨ 鹊桥回调
        try {
            queqiaoNotifyService.notifyNewData(syncVersion);
        } catch (Exception e) {
            log.error("[Inspection] 鹊桥回调异常: {}", e.getMessage());
        }

        log.info("[Inspection] 巡检完成: 在线{} 离线{} 异常{}", online, offline, abnormal);
    }

    /**
     * 异步执行巡检主体（控制器用）。
     * @Async 使该方法在独立线程中执行，调用方立即返回。
     */
    @Async
    public void runInspectionAsync(InspectionContext ctx) {
        runInspectionBody(ctx);
    }

    /**
     * 同步执行完整巡检（调度器用，行为不变）。
     * @Transactional 覆盖 prepare + body，整体一个事务。
     */
    @Transactional
    public Long executeInspection(String triggerType) {
        InspectionContext ctx = prepareInspection(triggerType);
        runInspectionBody(ctx);
        return ctx.getInspectId();
    }

    private CameraResult buildCameraResult(CameraConfig config, CameraCaptureResult capture, Long inspectId, long syncVersion) {
        CameraResult entity = new CameraResult();
        entity.setRecordId(inspectId);
        entity.setCameraCode(config.getCameraCode());
        entity.setCameraName(config.getCameraName());
        entity.setStatus(capture.getStatus());
        entity.setQualityScore(capture.getQualityScore() != null ? BigDecimal.valueOf(capture.getQualityScore()) : null);
        entity.setLocalScreenshotPath(capture.getScreenshotPath());
        entity.setScreenshotPath(uploadToMinio(capture.getScreenshotPath(), config.getCameraName()));
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

    /**
     * 读取本地截图文件并上传到 MinIO，返回 MinIO URL。
     * 读取失败或上传失败时返回原本地路径作为兜底，保证截图路径不丢失。
     */
    private String uploadToMinio(String localPath, String cameraName) {
        if (localPath == null || localPath.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(localPath));
            String url = minioStorageService.uploadScreenshot(cameraName, bytes);
            return url != null ? url : localPath;
        } catch (IOException e) {
            log.warn("[Inspection] 读取本地截图失败，保留本地路径 {}: {}", localPath, e.getMessage());
            return localPath;
        }
    }
}
