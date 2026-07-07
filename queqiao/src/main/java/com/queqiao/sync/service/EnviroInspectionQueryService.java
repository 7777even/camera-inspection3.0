package com.queqiao.sync.service;

import com.queqiao.sync.dto.view.CameraStatusView;
import com.queqiao.sync.dto.view.InspectionLedgerView;
import com.queqiao.sync.dto.view.InspectionSummaryView;
import com.queqiao.sync.entity.SyncedCameraResult;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import com.queqiao.sync.mapper.SyncedCameraResultMapper;
import com.queqiao.sync.mapper.SyncedInspectionRecordMapper;
import com.queqiao.sync.mapper.SyncedLedgerRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnviroInspectionQueryService {

    private final SyncedInspectionRecordMapper inspectionMapper;
    private final SyncedCameraResultMapper cameraMapper;
    private final SyncedLedgerRecordMapper ledgerMapper;

    /**
     * 获取指定日期巡查台账（巡检汇总 + 摄像头结果 + 台账记录）。
     * enterprise 为预留参数（synced_* 暂无该列），暂作 no-op。
     */
    public InspectionLedgerView getInspectionLedger(LocalDate date, String status, String enterprise) {
        LocalDate d = (date == null) ? LocalDate.now() : date;
        if (enterprise != null && !enterprise.isBlank()) {
            log.warn("[mcp][reserved] enterprise 过滤暂未启用：{}", enterprise);
        }
        SyncedInspectionRecord inspection = inspectionMapper.findByInspectionDate(d);
        if (inspection == null) {
            return InspectionLedgerView.empty(d);
        }
        List<SyncedCameraResult> cameras = cameraMapper.findByRecordId(inspection.getId());
        if (status != null && !status.isBlank()) {
            cameras = cameras.stream()
                    .filter(c -> status.equalsIgnoreCase(c.getStatus()))
                    .collect(Collectors.toList());
        }
        SyncedLedgerRecord ledger = ledgerMapper.findByRecordId(inspection.getId());
        InspectionLedgerView v = new InspectionLedgerView();
        v.setInspectionDate(d);
        v.setInspection(inspection);
        v.setCameras(cameras);
        v.setLedger(ledger);
        v.setSyncedAt(inspection.getSyncedAt());
        return v;
    }

    /**
     * 获取摄像头状态：指定 cameraName 返回最新快照 + 近 N 天历史；
     * 不指定则返回每摄像头最新一条。
     */
    public CameraStatusView getCameraStatus(String cameraName, Integer historyDays) {
        int days = (historyDays == null || historyDays < 1) ? 7 : historyDays;
        LocalDate since = LocalDate.now().minusDays(days);

        if (cameraName != null && !cameraName.isBlank()) {
            List<SyncedCameraResult> all = cameraMapper.findByCameraCode(cameraName);
            if (all.isEmpty()) {
                return CameraStatusView.empty();
            }
            SyncedCameraResult snapshot = all.get(0); // findByCameraCode 已按 synced_at DESC
            List<SyncedCameraResult> history = all.stream()
                    .filter(c -> c.getSyncedAt() == null
                            || !c.getSyncedAt().toLocalDate().isBefore(since))
                    .collect(Collectors.toList());
            CameraStatusView v = new CameraStatusView();
            v.setSnapshot(snapshot);
            v.setCameras(history);
            return v;
        }
        List<SyncedCameraResult> latest = cameraMapper.findLatestPerCamera();
        if (latest.isEmpty()) {
            return CameraStatusView.empty();
        }
        CameraStatusView v = new CameraStatusView();
        v.setCameras(latest);
        return v;
    }

    /**
     * 获取区间内巡检汇总：在线率、最差记录日、频繁离线摄像头排名。
     */
    public InspectionSummaryView getInspectionSummary(LocalDate start, LocalDate end) {
        List<SyncedInspectionRecord> records = inspectionMapper.findByRange(start, end);
        if (records.isEmpty()) {
            return InspectionSummaryView.empty(start, end);
        }
        int total = records.stream().mapToInt(SyncedInspectionRecord::getTotalCameras).sum();
        int online = records.stream().mapToInt(SyncedInspectionRecord::getOnlineCount).sum();
        double onlineRate = (total == 0) ? 0.0 : (double) online / total;

        SyncedInspectionRecord worstDay = records.stream()
                .max(Comparator.comparingInt(r -> r.getOfflineCount() + r.getAbnormalCount()))
                .orElse(records.get(0));

        List<Long> recordIds = records.stream().map(SyncedInspectionRecord::getId).collect(Collectors.toList());
        List<SyncedCameraResult> cameras = recordIds.isEmpty()
                ? List.of() : cameraMapper.findByRecordIds(recordIds);

        Map<String, Long> offlineCounts = cameras.stream()
                .filter(c -> "OFFLINE".equalsIgnoreCase(c.getStatus()))
                .collect(Collectors.groupingBy(SyncedCameraResult::getCameraCode, Collectors.counting()));
        List<Map<String, Object>> frequent = offlineCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cameraCode", e.getKey());
                    m.put("offlineCount", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        InspectionSummaryView v = new InspectionSummaryView();
        v.setStart(start);
        v.setEnd(end);
        v.setOnlineRate(onlineRate);
        v.setWorstDay(worstDay);
        v.setFrequentOfflineCameras(frequent);
        return v;
    }
}
