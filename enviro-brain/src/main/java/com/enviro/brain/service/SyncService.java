package com.enviro.brain.service;

import com.enviro.brain.dto.SyncResponse;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.entity.LedgerRecord;
import com.enviro.brain.mapper.CameraResultMapper;
import com.enviro.brain.mapper.InspectionRecordMapper;
import com.enviro.brain.mapper.LedgerRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SyncService {

    private static final int MAX_LIMIT = 5000;

    private final SyncVersionService syncVersionService;
    private final InspectionRecordMapper inspectionRecordMapper;
    private final CameraResultMapper cameraResultMapper;
    private final LedgerRecordMapper ledgerRecordMapper;

    /**
     * 获取当前水位（最大已分配同步版本号）
     */
    public long getWatermark() {
        return syncVersionService.getWatermark();
    }

    /**
     * 增量同步巡检记录
     */
    public SyncResponse<InspectionRecord> syncInspections(long since, int limit) {
        int cappedLimit = Math.min(limit, MAX_LIMIT);
        List<InspectionRecord> results =
                inspectionRecordMapper.findBySyncVersionGreaterThan(since, cappedLimit + 1);
        return buildResponse(results, cappedLimit, since);
    }

    /**
     * 增量同步摄像头结果
     */
    public SyncResponse<CameraResult> syncCameraResults(long since, int limit) {
        int cappedLimit = Math.min(limit, MAX_LIMIT);
        List<CameraResult> results =
                cameraResultMapper.findBySyncVersionGreaterThan(since, cappedLimit + 1);
        return buildResponse(results, cappedLimit, since);
    }

    /**
     * 增量同步台账记录
     */
    public SyncResponse<LedgerRecord> syncLedgerRecords(long since, int limit) {
        int cappedLimit = Math.min(limit, MAX_LIMIT);
        List<LedgerRecord> results =
                ledgerRecordMapper.findBySyncVersionGreaterThan(since, cappedLimit + 1);
        return buildResponse(results, cappedLimit, since);
    }

    private <T extends com.enviro.brain.entity.HasSyncVersion> SyncResponse<T> buildResponse(
            List<T> results, int limit, long since) {
        boolean hasMore = results.size() > limit;
        if (hasMore) {
            results = results.subList(0, limit);
        }
        long nextSince;
        if (results.isEmpty()) {
            nextSince = since;
        } else {
            nextSince = results.get(results.size() - 1).getSyncVersion();
        }
        return SyncResponse.of(results, hasMore, nextSince);
    }
}
