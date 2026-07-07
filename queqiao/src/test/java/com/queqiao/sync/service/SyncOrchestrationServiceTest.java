package com.queqiao.sync.service;

import com.queqiao.sync.client.EnviroBrainSyncClient;
import com.queqiao.sync.dto.CameraResultDto;
import com.queqiao.sync.dto.InspectionRecordDto;
import com.queqiao.sync.dto.LedgerRecordDto;
import com.queqiao.sync.dto.SyncResponse;
import com.queqiao.sync.entity.SyncWatermark;
import com.queqiao.sync.exception.SyncClientException;
import com.queqiao.sync.mapper.SyncWatermarkMapper;
import com.queqiao.sync.mapper.SyncedCameraResultMapper;
import com.queqiao.sync.mapper.SyncedInspectionRecordMapper;
import com.queqiao.sync.mapper.SyncedLedgerRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.queqiao.sync.AbstractQueqiaoTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@DisplayName("SyncOrchestrationService")
class SyncOrchestrationServiceTest extends AbstractQueqiaoTest {

    @MockBean
    private EnviroBrainSyncClient client;

    @Autowired
    private SyncOrchestrationService syncService;

    @Autowired
    private SyncedInspectionRecordMapper inspectionMapper;

    @Autowired
    private SyncedCameraResultMapper cameraMapper;

    @Autowired
    private SyncedLedgerRecordMapper ledgerMapper;

    @Autowired
    private SyncWatermarkMapper watermarkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM synced_inspection_records");
        jdbcTemplate.execute("DELETE FROM synced_camera_results");
        jdbcTemplate.execute("DELETE FROM synced_ledger_records");
        jdbcTemplate.execute("DELETE FROM sync_watermark");
    }

    // ---- helpers ----

    private <T> SyncResponse<T> resp(List<T> data, boolean hasMore, long nextSince) {
        return new SyncResponse<>(200, "success", data, hasMore, nextSince);
    }

    private InspectionRecordDto insp(long id, long sv) {
        InspectionRecordDto d = new InspectionRecordDto();
        d.setId(id);
        d.setBatchId("B" + id);
        d.setInspectionDate(LocalDate.of(2026, 7, 2));
        d.setTotalCameras(25);
        d.setOnlineCount(20);
        d.setOfflineCount(3);
        d.setAbnormalCount(2);
        d.setStatus("COMPLETED");
        d.setSyncVersion(sv);
        return d;
    }

    private CameraResultDto cam(long id, long sv) {
        CameraResultDto d = new CameraResultDto();
        d.setId(id);
        d.setRecordId(1L);
        d.setCameraCode("CAM-" + id);
        d.setCameraName("摄像头" + id);
        d.setStatus("ONLINE");
        d.setSyncVersion(sv);
        return d;
    }

    private LedgerRecordDto led(long id, long sv) {
        LedgerRecordDto d = new LedgerRecordDto();
        d.setId(id);
        d.setRecordId(1L);
        d.setInspectionDate(LocalDate.of(2026, 7, 2));
        d.setContent("内容" + id);
        d.setSyncVersion(sv);
        return d;
    }

    private void seedWatermark(String table, long version) {
        SyncWatermark w = new SyncWatermark();
        w.setTableName(table);
        w.setLastSyncVersion(version);
        w.setLastSyncTime(LocalDateTime.now());
        watermarkMapper.upsert(w);
    }

    private void stubEmpty(Class<?> type) {
        if (type == InspectionRecordDto.class) {
            when(client.syncInspections(anyLong(), anyInt()))
                    .thenReturn(resp(Collections.emptyList(), false, 0L));
        } else if (type == CameraResultDto.class) {
            when(client.syncCameraResults(anyLong(), anyInt()))
                    .thenReturn(resp(Collections.emptyList(), false, 0L));
        } else {
            when(client.syncLedgerRecords(anyLong(), anyInt()))
                    .thenReturn(resp(Collections.emptyList(), false, 0L));
        }
    }

    @Nested
    @DisplayName("增量拉取与水位推进")
    class IncrementalPull {

        @Test
        @DisplayName("有增量时拉取写库并推进水位")
        void shouldPullAndAdvanceWatermark() {
            when(client.getWatermark()).thenReturn(100L);
            when(client.syncInspections(anyLong(), anyInt()))
                    .thenReturn(resp(List.of(insp(1L, 10L), insp(2L, 20L)), false, 20L));
            stubEmpty(CameraResultDto.class);
            stubEmpty(LedgerRecordDto.class);

            SyncSummary summary = syncService.syncOnce();

            assertThat(inspectionMapper.count()).isEqualTo(2);
            assertThat(inspectionMapper.findById(2L).getSyncVersion()).isEqualTo(20L);
            assertThat(summary.getInspectionsWatermark()).isEqualTo(20L);
            assertThat(summary.getCameraResultsWatermark()).isEqualTo(0L);
            assertThat(summary.getLedgerRecordsWatermark()).isEqualTo(0L);
        }

        @Test
        @DisplayName("本地已追平远程时跳过整轮拉取")
        void shouldSkipWhenNoNewData() {
            seedWatermark(SyncOrchestrationService.TABLE_INSPECTIONS, 100L);
            seedWatermark(SyncOrchestrationService.TABLE_CAMERA_RESULTS, 100L);
            seedWatermark(SyncOrchestrationService.TABLE_LEDGER_RECORDS, 100L);
            when(client.getWatermark()).thenReturn(100L);

            syncService.syncOnce();

            verify(client, never()).syncInspections(anyLong(), anyInt());
            verify(client, never()).syncCameraResults(anyLong(), anyInt());
            verify(client, never()).syncLedgerRecords(anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("幂等性")
    class Idempotency {

        @Test
        @DisplayName("重复同步同一记录不增加行数")
        void shouldNotDuplicateOnReSync() {
            when(client.getWatermark()).thenReturn(100L);
            when(client.syncInspections(anyLong(), anyInt()))
                    .thenReturn(resp(List.of(insp(1L, 10L), insp(2L, 20L)), false, 20L));
            stubEmpty(CameraResultDto.class);
            stubEmpty(LedgerRecordDto.class);

            syncService.syncOnce();
            int afterFirst = inspectionMapper.count();
            syncService.syncOnce();

            assertThat(inspectionMapper.count()).isEqualTo(afterFirst);
            assertThat(watermarkMapper.selectByTableName("inspections").getLastSyncVersion())
                    .isEqualTo(20L);
        }
    }

    @Nested
    @DisplayName("失败容错")
    class FaultTolerance {

        @Test
        @DisplayName("单表失败不影响其他表，且不抛异常")
        void singleTableFailureIsolated() {
            when(client.getWatermark()).thenReturn(100L);
            when(client.syncInspections(anyLong(), anyInt()))
                    .thenReturn(resp(List.of(insp(1L, 10L), insp(2L, 20L)), false, 20L));
            when(client.syncCameraResults(anyLong(), anyInt()))
                    .thenThrow(new SyncClientException("环保小脑不可达"));
            stubEmpty(LedgerRecordDto.class);

            // 不应抛出异常
            SyncSummary summary = syncService.syncOnce();

            assertThat(inspectionMapper.count()).isEqualTo(2);
            // camera_results 失败，水位保持 0（未推进）
            assertThat(summary.getCameraResultsWatermark()).isEqualTo(0L);
            assertThat(cameraMapper.count()).isEqualTo(0);
            // ledger 仍正常同步（此处为空）
            assertThat(summary.getLedgerRecordsWatermark()).isEqualTo(0L);
        }

        @Test
        @DisplayName("游标退化时终止拉取避免死循环")
        void cursorDegradationGuard() {
            when(client.getWatermark()).thenReturn(100L);
            // 每次都返回 hasMore=true 且 nextSince 不推进 -> 模拟同 sync_version 分页退化
            when(client.syncInspections(anyLong(), anyInt()))
                    .thenReturn(resp(List.of(insp(1L, 10L)), true, 10L));
            stubEmpty(CameraResultDto.class);
            stubEmpty(LedgerRecordDto.class);

            syncService.syncOnce();

            // 仅写入首页一次，未陷入无限循环
            assertThat(inspectionMapper.count()).isEqualTo(1);
            assertThat(watermarkMapper.selectByTableName("inspections").getLastSyncVersion())
                    .isEqualTo(10L);
        }
    }
}
