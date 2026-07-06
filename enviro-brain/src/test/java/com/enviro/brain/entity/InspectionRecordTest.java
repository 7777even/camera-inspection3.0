package com.enviro.brain.entity;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: InspectionRecord doesn't exist yet.
 * This file should FAIL to compile.
 */
class InspectionRecordTest {
    @Test
    void should_create_with_required_fields() {
        InspectionRecord r = new InspectionRecord();
        r.setBatchId("BATCH-001");
        r.setInspectionDate(LocalDate.of(2026, 1, 1));
        r.setTotalCameras(10);
        r.setOnlineCount(8);
        r.setOfflineCount(1);
        r.setAbnormalCount(1);
        r.setStatus("COMPLETED");

        assertEquals("BATCH-001", r.getBatchId());
        assertEquals(LocalDate.of(2026, 1, 1), r.getInspectionDate());
    }

    @Test
    void should_implement_HasSyncVersion() {
        HasSyncVersion obj = new InspectionRecord();
        assertNull(obj.getSyncVersion());
    }
}
