package com.enviro.brain.entity;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: LedgerRecord doesn't exist yet.
 */
class LedgerRecordTest {
    @Test
    void should_create_with_required_fields() {
        LedgerRecord r = new LedgerRecord();
        r.setRecordId(1L);
        r.setInspectionDate(LocalDate.of(2026, 1, 1));
        r.setContent("Daily inspection ledger");
        r.setDocxPath("/path/to/ledger.docx");

        assertEquals(1L, r.getRecordId());
        assertEquals("Daily inspection ledger", r.getContent());
    }

    @Test
    void should_implement_HasSyncVersion() {
        HasSyncVersion obj = new LedgerRecord();
        assertEquals(0L, obj.getSyncVersion());
    }
}
