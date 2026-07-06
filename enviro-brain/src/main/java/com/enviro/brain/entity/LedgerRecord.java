package com.enviro.brain.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 台账记录实体
 */
@Data
public class LedgerRecord implements HasSyncVersion {
    private Long id;
    private Long recordId;
    private LocalDate inspectionDate;
    private String content;
    private String docxPath;
    private Long syncVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
