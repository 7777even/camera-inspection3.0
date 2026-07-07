package com.queqiao.sync.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 同步的摄像头结果实体，对应 {@code synced_camera_results} 表。
 */
@Data
public class SyncedCameraResult {
    private Long id;
    private Long recordId;
    private String cameraCode;
    private String cameraName;
    private String status;
    private BigDecimal qualityScore;
    private String screenshotPath;
    private String errorMessage;
    private Long syncVersion;
    private LocalDateTime syncedAt;
}
