package com.enviro.brain.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 摄像头巡检结果实体
 */
@Data
public class CameraResult implements HasSyncVersion {
    private Long id;
    private Long recordId;
    private String cameraCode;
    private String cameraName;
    private String status;
    private BigDecimal qualityScore;
    private String screenshotPath;
    private String errorMessage;
    private Long syncVersion;
    private LocalDateTime createdAt;
}
