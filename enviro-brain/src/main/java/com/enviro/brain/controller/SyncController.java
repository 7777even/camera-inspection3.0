package com.enviro.brain.controller;

import com.enviro.brain.dto.ApiResponse;
import com.enviro.brain.dto.SyncResponse;
import com.enviro.brain.dto.WatermarkResponse;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.entity.LedgerRecord;
import com.enviro.brain.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    /**
     * 查询当前同步水位
     */
    @GetMapping("/watermark")
    public ResponseEntity<ApiResponse<WatermarkResponse>> getWatermark() {
        long watermark = syncService.getWatermark();
        String serverTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        return ResponseEntity.ok(ApiResponse.success(new WatermarkResponse(watermark, serverTime)));
    }

    /**
     * 增量同步巡检记录
     */
    @GetMapping("/inspections")
    public ResponseEntity<SyncResponse<InspectionRecord>> syncInspections(
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "1000") int limit) {
        return ResponseEntity.ok(syncService.syncInspections(since, limit));
    }

    /**
     * 增量同步摄像头结果
     */
    @GetMapping("/camera-results")
    public ResponseEntity<SyncResponse<CameraResult>> syncCameraResults(
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "1000") int limit) {
        return ResponseEntity.ok(syncService.syncCameraResults(since, limit));
    }

    /**
     * 增量同步台账记录
     */
    @GetMapping("/ledger-records")
    public ResponseEntity<SyncResponse<LedgerRecord>> syncLedgerRecords(
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "1000") int limit) {
        return ResponseEntity.ok(syncService.syncLedgerRecords(since, limit));
    }
}
