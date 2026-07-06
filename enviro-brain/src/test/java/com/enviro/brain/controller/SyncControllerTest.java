package com.enviro.brain.controller;

import com.enviro.brain.dto.SyncResponse;
import com.enviro.brain.dto.WatermarkResponse;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.entity.LedgerRecord;
import com.enviro.brain.service.SyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SyncController.class)
@TestPropertySource(properties = "enviro.api.key=test-api-key")
@DisplayName("SyncController")
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SyncService syncService;

    @Nested
    @DisplayName("GET /api/v1/sync/watermark")
    class GetWatermark {

        @Test
        @DisplayName("should return watermark and serverTime")
        void shouldReturnWatermark() throws Exception {
            when(syncService.getWatermark()).thenReturn(42L);

            mockMvc.perform(get("/api/v1/sync/watermark")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.watermark").value(42))
                    .andExpect(jsonPath("$.data.serverTime").exists());
        }

        @Test
        @DisplayName("should return 401 without API key")
        void shouldReturn401WithoutApiKey() throws Exception {
            mockMvc.perform(get("/api/v1/sync/watermark"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/sync/inspections")
    class GetInspections {

        @Test
        @DisplayName("should return empty inspections")
        void shouldReturnEmptyInspections() throws Exception {
            SyncResponse<InspectionRecord> response =
                    SyncResponse.of(Collections.emptyList(), false, 0L);

            when(syncService.syncInspections(0L, 1000)).thenReturn(response);

            mockMvc.perform(get("/api/v1/sync/inspections")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(0)))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextSince").value(0));
        }

        @Test
        @DisplayName("should pass since and limit parameters")
        void shouldPassSinceAndLimitParams() throws Exception {
            SyncResponse<InspectionRecord> response =
                    SyncResponse.of(Collections.emptyList(), false, 5L);

            when(syncService.syncInspections(5L, 200)).thenReturn(response);

            mockMvc.perform(get("/api/v1/sync/inspections")
                            .header("X-API-Key", "test-api-key")
                            .param("since", "5")
                            .param("limit", "200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.nextSince").value(5));

            verify(syncService).syncInspections(5L, 200);
        }

        @Test
        @DisplayName("should use default since=0 and limit=1000")
        void shouldUseDefaults() throws Exception {
            when(syncService.syncInspections(0L, 1000))
                    .thenReturn(SyncResponse.of(Collections.emptyList(), false, 0L));

            mockMvc.perform(get("/api/v1/sync/inspections")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk());

            verify(syncService).syncInspections(0L, 1000);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/sync/camera-results")
    class GetCameraResults {

        @Test
        @DisplayName("should return camera results")
        void shouldReturnCameraResults() throws Exception {
            CameraResult r1 = new CameraResult();
            r1.setId(1L);
            r1.setSyncVersion(10L);
            CameraResult r2 = new CameraResult();
            r2.setId(2L);
            r2.setSyncVersion(12L);

            SyncResponse<CameraResult> response =
                    SyncResponse.of(Arrays.asList(r1, r2), false, 12L);

            when(syncService.syncCameraResults(0L, 1000)).thenReturn(response);

            mockMvc.perform(get("/api/v1/sync/camera-results")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextSince").value(12));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/sync/ledger-records")
    class GetLedgerRecords {

        @Test
        @DisplayName("should return ledger records with hasMore=true")
        void shouldReturnLedgerRecordsWithHasMore() throws Exception {
            LedgerRecord r1 = new LedgerRecord();
            r1.setId(1L);
            r1.setSyncVersion(8L);

            SyncResponse<LedgerRecord> response =
                    SyncResponse.of(Arrays.asList(r1), true, 8L);

            when(syncService.syncLedgerRecords(0L, 1000)).thenReturn(response);

            mockMvc.perform(get("/api/v1/sync/ledger-records")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.hasMore").value(true))
                    .andExpect(jsonPath("$.nextSince").value(8));
        }
    }
}
