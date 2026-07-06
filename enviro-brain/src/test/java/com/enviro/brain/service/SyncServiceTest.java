package com.enviro.brain.service;

import com.enviro.brain.dto.SyncResponse;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.entity.LedgerRecord;
import com.enviro.brain.mapper.CameraResultMapper;
import com.enviro.brain.mapper.InspectionRecordMapper;
import com.enviro.brain.mapper.LedgerRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncService")
class SyncServiceTest {

    @Mock
    private SyncVersionService syncVersionService;

    @Mock
    private InspectionRecordMapper inspectionRecordMapper;

    @Mock
    private CameraResultMapper cameraResultMapper;

    @Mock
    private LedgerRecordMapper ledgerRecordMapper;

    @InjectMocks
    private SyncService syncService;

    // ---- helpers ----

    private InspectionRecord inspection(long id, long syncVersion) {
        InspectionRecord r = new InspectionRecord();
        r.setId(id);
        r.setSyncVersion(syncVersion);
        return r;
    }

    private CameraResult cameraResult(long id, long syncVersion) {
        CameraResult r = new CameraResult();
        r.setId(id);
        r.setSyncVersion(syncVersion);
        return r;
    }

    private LedgerRecord ledgerRecord(long id, long syncVersion) {
        LedgerRecord r = new LedgerRecord();
        r.setId(id);
        r.setSyncVersion(syncVersion);
        return r;
    }

    @Nested
    @DisplayName("getWatermark()")
    class GetWatermark {

        @Test
        @DisplayName("should return current watermark")
        void shouldReturnCurrentWatermark() {
            when(syncVersionService.getWatermark()).thenReturn(5L);

            long watermark = syncService.getWatermark();

            assertThat(watermark).isEqualTo(5L);
            verify(syncVersionService).getWatermark();
        }
    }

    @Nested
    @DisplayName("syncInspections(since, limit)")
    class SyncInspections {

        @Test
        @DisplayName("should return empty when no data")
        void shouldReturnEmptyWhenNoData() {
            when(inspectionRecordMapper.findBySyncVersionGreaterThan(eq(0L), eq(11)))
                    .thenReturn(Collections.emptyList());

            SyncResponse<InspectionRecord> response = syncService.syncInspections(0L, 10);

            assertThat(response.getData()).isEmpty();
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextSince()).isZero();
        }

        @Test
        @DisplayName("should return data and detect hasMore=false when count <= limit")
        void shouldReturnDataWithoutHasMore() {
            InspectionRecord r1 = inspection(1L, 3L);
            InspectionRecord r2 = inspection(2L, 5L);
            when(inspectionRecordMapper.findBySyncVersionGreaterThan(eq(2L), eq(11)))
                    .thenReturn(Arrays.asList(r1, r2));

            SyncResponse<InspectionRecord> response = syncService.syncInspections(2L, 10);

            assertThat(response.getData()).hasSize(2);
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextSince()).isEqualTo(5L);
        }

        @Test
        @DisplayName("should detect hasMore=true when count > limit")
        void shouldDetectHasMore() {
            InspectionRecord r1 = inspection(1L, 3L);
            InspectionRecord r2 = inspection(2L, 5L);
            InspectionRecord r3 = inspection(3L, 7L);
            InspectionRecord r4 = inspection(4L, 9L);
            when(inspectionRecordMapper.findBySyncVersionGreaterThan(eq(0L), eq(4)))
                    .thenReturn(Arrays.asList(r1, r2, r3, r4));

            SyncResponse<InspectionRecord> response = syncService.syncInspections(0L, 3);

            assertThat(response.getData()).hasSize(3);
            assertThat(response.isHasMore()).isTrue();
            assertThat(response.getNextSince()).isEqualTo(7L);
        }

        @Test
        @DisplayName("should clamp limit to 5000")
        void shouldClampLimitToMax() {
            when(inspectionRecordMapper.findBySyncVersionGreaterThan(eq(0L), eq(5001)))
                    .thenReturn(Collections.emptyList());

            syncService.syncInspections(0L, 10000);

            verify(inspectionRecordMapper).findBySyncVersionGreaterThan(0L, 5001);
        }
    }

    @Nested
    @DisplayName("syncCameraResults(since, limit)")
    class SyncCameraResults {

        @Test
        @DisplayName("should return camera results")
        void shouldReturnCameraResults() {
            CameraResult r1 = cameraResult(1L, 10L);
            CameraResult r2 = cameraResult(2L, 12L);
            when(cameraResultMapper.findBySyncVersionGreaterThan(eq(0L), eq(1001)))
                    .thenReturn(Arrays.asList(r1, r2));

            SyncResponse<CameraResult> response = syncService.syncCameraResults(0L, 1000);

            assertThat(response.getData()).hasSize(2);
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextSince()).isEqualTo(12L);
        }
    }

    @Nested
    @DisplayName("syncLedgerRecords(since, limit)")
    class SyncLedgerRecords {

        @Test
        @DisplayName("should return ledger records")
        void shouldReturnLedgerRecords() {
            LedgerRecord r1 = ledgerRecord(1L, 8L);
            when(ledgerRecordMapper.findBySyncVersionGreaterThan(eq(5L), eq(1001)))
                    .thenReturn(List.of(r1));

            SyncResponse<LedgerRecord> response = syncService.syncLedgerRecords(5L, 1000);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextSince()).isEqualTo(8L);
        }
    }
}
