package com.enviro.brain.entity;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: CameraResult doesn't exist yet.
 */
class CameraResultTest {
    @Test
    void should_create_with_required_fields() {
        CameraResult r = new CameraResult();
        r.setRecordId(1L);
        r.setCameraCode("CAM-001");
        r.setCameraName("Test Camera");
        r.setStatus("ONLINE");
        r.setQualityScore(new BigDecimal("85.50"));

        assertEquals("CAM-001", r.getCameraCode());
        assertEquals("ONLINE", r.getStatus());
    }

    @Test
    void should_implement_HasSyncVersion() {
        HasSyncVersion obj = new CameraResult();
        assertEquals(0L, obj.getSyncVersion());
    }
}
