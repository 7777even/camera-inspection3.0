package com.enviro.brain.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED: CameraConfig doesn't exist yet.
 * Note: CameraConfig does NOT implement HasSyncVersion.
 */
class CameraConfigTest {
    @Test
    void should_create_with_required_fields() {
        CameraConfig c = new CameraConfig();
        c.setCameraCode("CAM-001");
        c.setCameraName("Test Camera");
        c.setRtspUrl("rtsp://192.168.1.100:554/stream");
        c.setLocation("Building A Floor 1");
        c.setEnabled(1);

        assertEquals("CAM-001", c.getCameraCode());
        assertEquals("Test Camera", c.getCameraName());
    }

    @Test
    void should_not_implement_HasSyncVersion() {
        // CameraConfig is NOT a HasSyncVersion - verify by type check
        CameraConfig c = new CameraConfig();
        assertFalse(c instanceof HasSyncVersion);
    }
}
