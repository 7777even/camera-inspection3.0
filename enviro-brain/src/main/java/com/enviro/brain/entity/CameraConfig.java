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
    private String enterprise;
    private String artemisDeviceId;
    private String rtspUrl;
    private String location;
    private Integer enabled;
    /** 是否更新到台账（1=是，0=否，来自 Excel 清单） */
    private Integer ledgerEnabled;
    /** 场景维度：enviro=环保小脑（默认），gangqu=港区小脑 */
    private String scenario = "enviro";
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
