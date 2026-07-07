package com.queqiao.sync.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 台账记录 DTO，字段 1:1 镜像环保小脑 {@code LedgerRecord} 实体。
 */
@Data
@NoArgsConstructor
public class LedgerRecordDto {
    private Long id;
    private Long recordId;
    private LocalDate inspectionDate;
    private String content;
    private String docxPath;
    private Long syncVersion;
    private LocalDateTime createdAt;
}
