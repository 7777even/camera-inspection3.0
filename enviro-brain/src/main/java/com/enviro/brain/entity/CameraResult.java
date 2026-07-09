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
    /** 本地截图文件绝对路径；台账 docx 嵌入图片时使用（screenshotPath 存的是 MinIO URL） */
    private String localScreenshotPath;
    private String errorMessage;
    private Long syncVersion = 0L;
    private LocalDateTime createdAt;
}
