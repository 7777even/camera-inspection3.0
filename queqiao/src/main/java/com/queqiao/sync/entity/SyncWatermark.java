package com.queqiao.sync.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步水位实体，对应 {@code sync_watermark} 表。
 */
@Data
public class SyncWatermark {
    private Integer id;
    private String tableName;
    private Long lastSyncVersion;
    private LocalDateTime lastSyncTime;
    private LocalDateTime updatedAt;
}
