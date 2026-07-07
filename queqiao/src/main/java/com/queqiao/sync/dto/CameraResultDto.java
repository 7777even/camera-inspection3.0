package com.queqiao.sync.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 摄像头结果 DTO，字段 1:1 镜像环保小脑 {@code CameraResult} 实体。
 */
@Data
@NoArgsConstructor
public class CameraResultDto {
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
