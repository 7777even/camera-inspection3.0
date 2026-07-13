package com.enviro.brain.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 巡检记录实体
 */
@Data
public class InspectionRecord implements HasSyncVersion {
    private Long id;
    private String batchId;
    private LocalDate inspectionDate;
    private Integer totalCameras;
    private Integer onlineCount;
    private Integer offlineCount;
    private Integer abnormalCount;
    private String status;
    /** 场景维度：enviro=环保小脑（默认），gangqu=港区小脑 */
    private String scenario = "enviro";
    private Long syncVersion = 0L;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
