package com.queqiao.sync.service;

import com.queqiao.sync.client.EnviroBrainSyncClient;
import com.queqiao.sync.dto.CameraResultDto;
import com.queqiao.sync.dto.InspectionRecordDto;
import com.queqiao.sync.dto.LedgerRecordDto;
import com.queqiao.sync.dto.SyncResponse;
import com.queqiao.sync.entity.SyncedCameraResult;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import com.queqiao.sync.entity.SyncWatermark;
import com.queqiao.sync.mapper.SyncedCameraResultMapper;
import com.queqiao.sync.mapper.SyncedInspectionRecordMapper;
import com.queqiao.sync.mapper.SyncedLedgerRecordMapper;
import com.queqiao.sync.mapper.SyncWatermarkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 同步编排服务。
 * 每轮执行：拉取环保小脑水位 → 逐表比对本地水位 → 以 nextSince 游标循环拉取增量
 * → 幂等写入 synced_* 表 → 更新 sync_watermark 水位。
 *
 * 容错：单表失败仅记日志并继续其他表；游标退化（同 sync_version 分页）时主动终止该表拉取避免死循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncOrchestrationService {

    /** 表名常量，与 sync_watermark.table_name 对应 */
    public static final String TABLE_INSPECTIONS = "inspections";
    public static final String TABLE_CAMERA_RESULTS = "camera_results";
    public static final String TABLE_LEDGER_RECORDS = "ledger_records";

    private final EnviroBrainSyncClient client;
    private final SyncedInspectionRecordMapper inspectionMapper;
    private final SyncedCameraResultMapper cameraMapper;
    private final SyncedLedgerRecordMapper ledgerMapper;
    private final SyncWatermarkMapper watermarkMapper;

    @Value("${queqiao.sync.batch-limit:200}")
    private int batchLimit;

    /**
     * 执行一轮完整增量同步。
     *
     * @return 本轮同步摘要（各表推进后的水位）
     * @throws RuntimeException 当获取远程水位失败时抛出（由调度层捕获并容忍）
     */
    public SyncSummary syncOnce() {
        long remoteWatermark = client.getWatermark();
        log.info("[sync] 远程水位 = {}", remoteWatermark);

        long insW = pullAndWrite(TABLE_INSPECTIONS, remoteWatermark,
                client::syncInspections,
                this::toInspectionEntity,
                inspectionMapper::upsert);
        setLocalWatermark(TABLE_INSPECTIONS, insW);

        long camW = pullAndWrite(TABLE_CAMERA_RESULTS, remoteWatermark,
                client::syncCameraResults,
                this::toCameraEntity,
                cameraMapper::upsert);
        setLocalWatermark(TABLE_CAMERA_RESULTS, camW);

        long ledW = pullAndWrite(TABLE_LEDGER_RECORDS, remoteWatermark,
                client::syncLedgerRecords,
                this::toLedgerEntity,
                ledgerMapper::upsert);
        setLocalWatermark(TABLE_LEDGER_RECORDS, ledW);

        log.info("[sync] 本轮完成：inspections={}, camera_results={}, ledger_records={}",
                insW, camW, ledW);
        return new SyncSummary(remoteWatermark, insW, camW, ledW);
    }

    /**
     * 逐表拉取并幂等写入，返回推进后的本地水位。
     * 容错策略见设计文档 D3/D6。
     */
    private <D, E> long pullAndWrite(String table,
                                     long remoteWatermark,
                                     BiFunction<Long, Integer, SyncResponse<D>> puller,
                                     Function<D, E> toEntity,
                                     Consumer<E> upsert) {
        long localWatermark = getLocalWatermark(table);

        // 水位比对：本地已追平或超前远程，跳过该表
        if (localWatermark >= remoteWatermark) {
            log.info("[sync] {} 无新数据（本地水位 {} >= 远程 {}），跳过", table, localWatermark, remoteWatermark);
            return localWatermark;
        }

        long cursor = localWatermark;
        try {
            while (true) {
                SyncResponse<D> resp = puller.apply(cursor, batchLimit);
                List<D> items = (resp == null || resp.getData() == null)
                        ? Collections.emptyList() : resp.getData();
                if (items.isEmpty()) {
                    break;
                }
                for (D item : items) {
                    upsert.accept(toEntity.apply(item));
                }
                long nextSince = resp.getNextSince();
                if (!resp.isHasMore()) {
                    cursor = nextSince;
                    break;
                }
                // 游标防护：无法推进时终止该表拉取，避免死循环
                if (nextSince <= cursor) {
                    log.warn("[sync] {} 检测到游标退化（nextSince={} <= since={}），终止拉取以避免死循环",
                            table, nextSince, cursor);
                    break;
                }
                cursor = nextSince;
            }
        } catch (Exception e) {
            // 单表失败不影响其他表，本轮不更新该表水位
            log.error("[sync] {} 同步过程中发生异常，本轮跳过该表", table, e);
            return localWatermark;
        }
        return cursor;
    }

    private long getLocalWatermark(String table) {
        SyncWatermark w = watermarkMapper.selectByTableName(table);
        return (w == null) ? 0L : w.getLastSyncVersion();
    }

    private void setLocalWatermark(String table, long watermark) {
        SyncWatermark w = new SyncWatermark();
        w.setTableName(table);
        w.setLastSyncVersion(watermark);
        w.setLastSyncTime(LocalDateTime.now());
        watermarkMapper.upsert(w);
    }

    // ---- DTO -> 实体映射 ----

    private SyncedInspectionRecord toInspectionEntity(InspectionRecordDto d) {
        SyncedInspectionRecord e = new SyncedInspectionRecord();
        e.setId(d.getId());
        e.setBatchId(d.getBatchId());
        e.setInspectionDate(d.getInspectionDate());
        e.setTotalCameras(d.getTotalCameras());
        e.setOnlineCount(d.getOnlineCount());
        e.setOfflineCount(d.getOfflineCount());
        e.setAbnormalCount(d.getAbnormalCount());
        e.setStatus(d.getStatus());
        e.setSyncVersion(d.getSyncVersion());
        return e;
    }

    private SyncedCameraResult toCameraEntity(CameraResultDto d) {
        SyncedCameraResult e = new SyncedCameraResult();
        e.setId(d.getId());
        e.setRecordId(d.getRecordId());
        e.setCameraCode(d.getCameraCode());
        e.setCameraName(d.getCameraName());
        e.setStatus(d.getStatus());
        e.setQualityScore(d.getQualityScore());
        e.setScreenshotPath(d.getScreenshotPath());
        e.setErrorMessage(d.getErrorMessage());
        e.setSyncVersion(d.getSyncVersion());
        return e;
    }

    private SyncedLedgerRecord toLedgerEntity(LedgerRecordDto d) {
        SyncedLedgerRecord e = new SyncedLedgerRecord();
        e.setId(d.getId());
        e.setRecordId(d.getRecordId());
        e.setInspectionDate(d.getInspectionDate());
        e.setContent(d.getContent());
        e.setDocxPath(d.getDocxPath());
        e.setSyncVersion(d.getSyncVersion());
        return e;
    }
}
