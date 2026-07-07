package com.queqiao.sync.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 同步的台账记录实体，对应 {@code synced_ledger_records} 表。
 */
@Data
public class SyncedLedgerRecord {
    private Long id;
    private Long recordId;
    private LocalDate inspectionDate;
    private String content;
    private String docxPath;
    private Long syncVersion;
    private LocalDateTime syncedAt;
}
