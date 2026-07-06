package com.enviro.brain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WatermarkResponse")
class WatermarkResponseTest {

    @Test
    @DisplayName("should store watermark and serverTime")
    void shouldStoreWatermarkAndServerTime() {
        WatermarkResponse response = new WatermarkResponse(42L, "2024-01-01T00:00:00");

        assertThat(response.getWatermark()).isEqualTo(42L);
        assertThat(response.getServerTime()).isEqualTo("2024-01-01T00:00:00");
    }

    @Test
    @DisplayName("should support zero watermark")
    void shouldSupportZeroWatermark() {
        WatermarkResponse response = new WatermarkResponse(0L, "2024-01-01T00:00:00");

        assertThat(response.getWatermark()).isZero();
    }
}
