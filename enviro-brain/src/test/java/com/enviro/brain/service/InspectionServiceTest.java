package com.enviro.brain.service;

import com.enviro.brain.config.ScenarioConfig;
import com.enviro.brain.config.ScenarioConfigs;
import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.dto.InspectionContext;
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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
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
    @Mock private QueqiaoNotifyService queqiaoNotifyService;
    @Mock private MinioStorageService minioStorageService;
    @Mock private ScenarioConfigs scenarioConfigs;

    @InjectMocks
    private InspectionService inspectionService;

    @BeforeEach
    void setUp() throws Exception {
        Field concurrencyField = InspectionService.class.getDeclaredField("concurrency");
        concurrencyField.setAccessible(true);
        concurrencyField.set(inspectionService, 2);
        Field timeoutField = InspectionService.class.getDeclaredField("captureTimeoutSeconds");
        timeoutField.setAccessible(true);
        timeoutField.set(inspectionService, 30);
    }

    private List<CameraConfig> cameras() {
        CameraConfig c1 = new CameraConfig();
        c1.setCameraCode("CAM001"); c1.setCameraName("危废1"); c1.setEnabled(1);
        CameraConfig c2 = new CameraConfig();
        c2.setCameraCode("CAM002"); c2.setCameraName("危废2"); c2.setEnabled(1);
        return Arrays.asList(c1, c2);
    }

    private CameraConfig gangquCamera() {
        CameraConfig c = new CameraConfig();
        c.setCameraCode("G1"); c.setCameraName("港区1"); c.setEnabled(1);
        c.setScenario("gangqu");
        return c;
    }

    private CameraCaptureResult okCapture() throws Exception {
        Path p = Files.createTempFile("cap", ".jpg");
        Files.write(p, new byte[]{1, 2, 3});
        CameraCaptureResult r = new CameraCaptureResult();
        r.setStatus("online");
        r.setQualityScore(0.9);
        r.setScreenshotPath(p.toString());
        return r;
    }

    @Nested
    @DisplayName("executeInspection()")
    class ExecuteInspection {

        @Test
        @DisplayName("should complete full inspection flow")
        void shouldCompleteFullFlow() throws Exception {
            when(cameraConfigService.findActiveByScenario(anyInt(), anyInt(), eq("enviro"))).thenReturn(cameras());
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
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any(), any());

            Long inspectId = inspectionService.executeInspection("auto", "enviro");

            assertThat(inspectId).isNotNull();
            verify(syncVersionService).nextVersion();
            verify(inspectionRecordMapper).insert(any(InspectionRecord.class));
            verify(cameraResultMapper).batchInsert(anyList());
            verify(inspectionRecordMapper).updateById(any(InspectionRecord.class));
            verify(ledgerService).generateAndSave(anyLong(), anyList(), eq(42L));
            verify(feishuNotifyService).sendInspectionReport(any(), any(), any());
        }

        @Test
        @DisplayName("should handle partial failures gracefully")
        void shouldHandlePartialFailures() throws Exception {
            when(cameraConfigService.findActiveByScenario(anyInt(), anyInt(), eq("enviro"))).thenReturn(cameras());
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
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any(), any());

            Long inspectId = inspectionService.executeInspection("auto", "enviro");

            assertThat(inspectId).isNotNull();
            verify(cameraResultMapper).batchInsert(anyList());
        }

        @Test
        @DisplayName("should handle all capture failures")
        void shouldHandleAllFailures() throws Exception {
            when(cameraConfigService.findActiveByScenario(anyInt(), anyInt(), eq("enviro"))).thenReturn(cameras());
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
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any(), any());

            Long inspectId = inspectionService.executeInspection("auto", "enviro");

            assertThat(inspectId).isNotNull();
        }

        @Test
        @DisplayName("should handle empty camera list")
        void shouldHandleEmptyCameraList() throws Exception {
            when(cameraConfigService.findActiveByScenario(anyInt(), anyInt(), eq("enviro"))).thenReturn(Collections.emptyList());
            when(syncVersionService.nextVersion()).thenReturn(42L);
            doAnswer(invocation -> {
                InspectionRecord rec = invocation.getArgument(0);
                rec.setId(1L);
                return null;
            }).when(inspectionRecordMapper).insert(any(InspectionRecord.class));
            doNothing().when(inspectionRecordMapper).updateById(any(InspectionRecord.class));
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any(), any());

            Long inspectId = inspectionService.executeInspection("auto", "enviro");

            assertThat(inspectId).isNotNull();
            verify(cameraResultMapper, never()).batchInsert(anyList());
        }

        @Test
        @DisplayName("should correctly count online/offline/abnormal")
        void shouldCorrectlyCount() throws Exception {
            when(cameraConfigService.findActiveByScenario(anyInt(), anyInt(), eq("enviro"))).thenReturn(cameras());
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
            doNothing().when(feishuNotifyService).sendInspectionReport(any(), any(), any());

            inspectionService.executeInspection("auto", "enviro");

            InspectionRecord updated = captor.getValue();
            assertThat(updated.getOnlineCount()).isEqualTo(1);
            assertThat(updated.getOfflineCount()).isEqualTo(1);
            assertThat(updated.getAbnormalCount()).isEqualTo(0);
            assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        }
    }

        @Nested
        @DisplayName("prepareInspection()")
        class PrepareInspection {

            @Test
            @DisplayName("should create RUNNING record and return context")
            void shouldCreateRunningRecord() {
                when(cameraConfigService.findActiveByScenario(anyInt(), anyInt(), eq("enviro"))).thenReturn(cameras());
                when(syncVersionService.nextVersion()).thenReturn(42L);
                doAnswer(invocation -> {
                    InspectionRecord rec = invocation.getArgument(0);
                    rec.setId(1L);
                    return null;
                }).when(inspectionRecordMapper).insert(any(InspectionRecord.class));

                InspectionContext ctx = inspectionService.prepareInspection("manual", "enviro");

                assertThat(ctx).isNotNull();
                assertThat(ctx.getInspectId()).isEqualTo(1L);
                assertThat(ctx.getSyncVersion()).isEqualTo(42L);
                assertThat(ctx.getCameras()).hasSize(2);
                assertThat(ctx.getRecord().getStatus()).isEqualTo("RUNNING");
                assertThat(ctx.getRecord().getScenario()).isEqualTo("enviro");
                verify(inspectionRecordMapper).insert(any(InspectionRecord.class));
                verify(inspectionRecordMapper, never()).updateById(any());
            }
        }

    @Test
    @DisplayName("should build batchId with scenario prefix and route minio prefix")
    void shouldBuildBatchIdWithScenarioPrefixAndRouteMinioPrefix() throws Exception {
        CameraConfig gangqu = gangquCamera();
        ScenarioConfig gangquCfg = new ScenarioConfig();
        gangquCfg.setMinioPrefix("gangqu");
        when(scenarioConfigs.getOrDefault("gangqu")).thenReturn(gangquCfg);
        when(cameraConfigService.findActiveByScenario(anyInt(), anyInt(), eq("gangqu")))
                .thenReturn(List.of(gangqu));
        when(syncVersionService.nextVersion()).thenReturn(7L);
        when(captureService.capture(any())).thenReturn(okCapture());
        when(minioStorageService.uploadScreenshot(anyString(), any(byte[].class), eq("gangqu"))).thenReturn("http://minio/gangqu/x.jpg");

        InspectionContext ctx = inspectionService.prepareInspection("manual", "gangqu");
        assertThat(ctx.getRecord().getBatchId()).startsWith("gangqu-manual-");
        assertThat(ctx.getRecord().getScenario()).isEqualTo("gangqu");

        inspectionService.runInspectionBody(ctx);

        verify(minioStorageService).uploadScreenshot(anyString(), any(byte[].class), eq("gangqu"));
        verify(feishuNotifyService).sendInspectionReport(any(), any(), eq("gangqu"));
    }
}
