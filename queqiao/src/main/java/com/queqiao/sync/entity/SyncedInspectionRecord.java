package com.queqiao.sync.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 同步的巡检记录实体，对应 {@code synced_inspection_records} 表。
 */
@Data
public class SyncedInspectionRecord {
    private Long id;
    private String batchId;
    private LocalDate inspectionDate;
    private Integer totalCameras;
    private Integer onlineCount;
    private Integer offlineCount;
    private Integer abnormalCount;
    private String status;
    private Long syncVersion;
    private LocalDateTime syncedAt;
}
