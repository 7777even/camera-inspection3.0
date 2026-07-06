package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.mapper.CameraResultMapper;
import com.enviro.brain.mapper.InspectionRecordMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InspectionService")
class InspectionServiceTest {

    @Mock private CameraConfigService cameraConfigService;
    @Mock private CaptureService captureService;
    @Mock private SyncVersionService syncVersionService;
    @Mock private InspectionRecordMapper inspectionRecordMapper;
    @Mock private CameraResultMapper cameraResultMapper;
    @Mock private LedgerService ledgerService;
    @Mock private FeishuNotifyService feishuNotifyService;

    @InjectMocks
    private InspectionService inspectionService;

    private List<CameraConfig> cameras() {
        CameraConfig c1 = new CameraConfig();
        c1.setCameraCode("CAM001"); c1.setCameraName("危废1"); c1.setEnabled(1);
        CameraConfig c2 = new CameraConfig();
        c2.setCameraCode("CAM002"); c2.setCameraName("危废2"); c2.setEnabled(1);
        return Arrays.asList(c1, c2);
    }

    @Nested
    @DisplayName("executeInspection()")
    class ExecuteInspection {

        @Test
        @DisplayName("should complete full inspection flow")
        void shouldCompleteFullFlow() throws Exception {
            when(cameraConfigService.findActive(anyInt(), anyInt())).thenReturn(cameras());
            when(syncVersionService.nextVersion()).thenReturn(42L);
            doAnswer(invocation -> {
                InspectionRecord rec = invocation.getArgument(0);
                rec.setId(1L);
                return null;
            }).when(inspectionRecordMapper).insert(any(InspectionRecord.class));

            CameraCaptureResult ok = new CameraCaptureResult();
            ok.setStatus("online"); ok.setQualityScore(0.85);
            when(captureService.capture(any(CameraConfig.class))).thenReturn(ok);

            doNothing().when(cameraResultMapper).batchInsert(anyList());
            doNothing().when(inspectionRecordMapper).updateById(any(InspectionRecord.class));
            when(ledgerService.generateAndSave(anyLong(), anyList(), anyLong())).thenReturn("/ledger/test.docx");
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any());

            Long inspectId = inspectionService.executeInspection("auto");

            assertThat(inspectId).isNotNull();
            verify(syncVersionService).nextVersion();
            verify(inspectionRecordMapper).insert(any(InspectionRecord.class));
            verify(cameraResultMapper).batchInsert(anyList());
            verify(inspectionRecordMapper).updateById(any(InspectionRecord.class));
            verify(ledgerService).generateAndSave(anyLong(), anyList(), eq(42L));
            verify(feishuNotifyService).sendInspectionReport(any(), any());
        }

        @Test
        @DisplayName("should handle partial failures gracefully")
        void shouldHandlePartialFailures() throws Exception {
            when(cameraConfigService.findActive(anyInt(), anyInt())).thenReturn(cameras());
            when(syncVersionService.nextVersion()).thenReturn(42L);
            doAnswer(invocation -> {
                InspectionRecord rec = invocation.getArgument(0);
                rec.setId(1L);
                return null;
            }).when(inspectionRecordMapper).insert(any(InspectionRecord.class));

            // First camera succeeds, second fails
            CameraCaptureResult ok = new CameraCaptureResult();
            ok.setStatus("online"); ok.setQualityScore(0.85);
            when(captureService.capture(any(CameraConfig.class)))
                    .thenReturn(ok)
                    .thenThrow(new RuntimeException("Python crash"));

            doNothing().when(cameraResultMapper).batchInsert(anyList());
            doNothing().when(inspectionRecordMapper).updateById(any(InspectionRecord.class));
            when(ledgerService.generateAndSave(anyLong(), anyList(), anyLong())).thenReturn(null);
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any());

            Long inspectId = inspectionService.executeInspection("auto");

            assertThat(inspectId).isNotNull();
            verify(cameraResultMapper).batchInsert(anyList());
        }

        @Test
        @DisplayName("should handle all capture failures")
        void shouldHandleAllFailures() throws Exception {
            when(cameraConfigService.findActive(anyInt(), anyInt())).thenReturn(cameras());
            when(syncVersionService.nextVersion()).thenReturn(42L);
            doAnswer(invocation -> {
                InspectionRecord rec = invocation.getArgument(0);
                rec.setId(1L);
                return null;
            }).when(inspectionRecordMapper).insert(any(InspectionRecord.class));
            when(captureService.capture(any(CameraConfig.class)))
                    .thenThrow(new RuntimeException("Python not found"));

            doNothing().when(cameraResultMapper).batchInsert(anyList());
            doNothing().when(inspectionRecordMapper).updateById(any(InspectionRecord.class));
            when(ledgerService.generateAndSave(anyLong(), anyList(), anyLong())).thenReturn(null);
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any());

            Long inspectId = inspectionService.executeInspection("auto");

            assertThat(inspectId).isNotNull();
        }

        @Test
        @DisplayName("should handle empty camera list")
        void shouldHandleEmptyCameraList() throws Exception {
            when(cameraConfigService.findActive(anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(syncVersionService.nextVersion()).thenReturn(42L);
            doAnswer(invocation -> {
                InspectionRecord rec = invocation.getArgument(0);
                rec.setId(1L);
                return null;
            }).when(inspectionRecordMapper).insert(any(InspectionRecord.class));
            doNothing().when(inspectionRecordMapper).updateById(any(InspectionRecord.class));
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any());

            Long inspectId = inspectionService.executeInspection("auto");

            assertThat(inspectId).isNotNull();
            verify(cameraResultMapper, never()).batchInsert(anyList());
        }

        @Test
        @DisplayName("should correctly count online/offline/abnormal")
        void shouldCorrectlyCount() throws Exception {
            when(cameraConfigService.findActive(anyInt(), anyInt())).thenReturn(cameras());
            when(syncVersionService.nextVersion()).thenReturn(42L);

            CameraCaptureResult online = new CameraCaptureResult();
            online.setStatus("online");
            CameraCaptureResult offline = new CameraCaptureResult();
            offline.setStatus("offline");

            when(captureService.capture(any(CameraConfig.class)))
                    .thenReturn(online).thenReturn(offline);

            doAnswer(invocation -> {
                InspectionRecord rec = invocation.getArgument(0);
                rec.setId(1L);
                return null;
            }).when(inspectionRecordMapper).insert(any());
            doNothing().when(cameraResultMapper).batchInsert(anyList());

            ArgumentCaptor<InspectionRecord> captor = ArgumentCaptor.forClass(InspectionRecord.class);
            doNothing().when(inspectionRecordMapper).updateById(captor.capture());
            when(ledgerService.generateAndSave(anyLong(), anyList(), anyLong())).thenReturn(null);
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any());

            inspectionService.executeInspection("auto");

            InspectionRecord updated = captor.getValue();
            assertThat(updated.getOnlineCount()).isEqualTo(1);
            assertThat(updated.getOfflineCount()).isEqualTo(1);
            assertThat(updated.getAbnormalCount()).isEqualTo(0);
            assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        }
    }
}
