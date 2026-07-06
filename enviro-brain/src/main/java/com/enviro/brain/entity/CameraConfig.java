package com.enviro.brain.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 摄像头配置实体
 * 注意：CameraConfig 不实现 HasSyncVersion（不同步）
 */
@Data
public class CameraConfig {
    private Long id;
    private String cameraCode;
    private String cameraName;
    private String rtspUrl;
    private String location;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
