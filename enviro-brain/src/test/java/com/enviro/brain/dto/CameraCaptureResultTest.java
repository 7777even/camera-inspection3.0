package com.enviro.brain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CameraCaptureResult")
class CameraCaptureResultTest {

    @Test
    @DisplayName("should have all fields accessible via Lombok")
    void shouldAccessAllFields() {
        CameraCaptureResult result = new CameraCaptureResult();
        result.setStatus("online");
        result.setQualityScore(0.85);
        result.setScreenshotPath("/path/to/screenshot.jpg");
        result.setErrorMsg(null);
        result.setCaptureTime("2026-07-06 15:01:23");
        result.setRetryUsed(0);
        result.setQualityDetail("{\"laplacian\": 128.5}");

        assertThat(result.getStatus()).isEqualTo("online");
        assertThat(result.getQualityScore()).isEqualTo(0.85);
        assertThat(result.getScreenshotPath()).isEqualTo("/path/to/screenshot.jpg");
        assertThat(result.getErrorMsg()).isNull();
        assertThat(result.getCaptureTime()).isEqualTo("2026-07-06 15:01:23");
        assertThat(result.getRetryUsed()).isEqualTo(0);
        assertThat(result.getQualityDetail()).isEqualTo("{\"laplacian\": 128.5}");
    }

    @Test
    @DisplayName("should default retryUsed to 0")
    void shouldDefaultRetryUsedToZero() {
        CameraCaptureResult result = new CameraCaptureResult();
        assertThat(result.getRetryUsed()).isEqualTo(0);
    }
}
