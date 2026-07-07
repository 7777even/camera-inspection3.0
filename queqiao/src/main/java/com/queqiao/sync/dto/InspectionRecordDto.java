package com.queqiao.sync.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 巡检记录 DTO，字段 1:1 镜像环保小脑 {@code InspectionRecord} 实体。
 */
@Data
@NoArgsConstructor
public class InspectionRecordDto {
    private Long id;
    private String batchId;
    private LocalDate inspectionDate;
    private Integer totalCameras;
    private Integer onlineCount;
    private Integer offlineCount;
    private Integer abnormalCount;
    private String status;
    private Long syncVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
