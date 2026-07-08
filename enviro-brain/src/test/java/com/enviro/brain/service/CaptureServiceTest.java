package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.entity.CameraConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CaptureService")
class CaptureServiceTest {

    private CaptureService captureService;

    @BeforeEach
    void setUp() {
        captureService = new CaptureService();
        ReflectionTestUtils.setField(captureService, "pythonPath", "python3");
        ReflectionTestUtils.setField(captureService, "captureScript", "scripts/camera_capture.py");
        ReflectionTestUtils.setField(captureService, "hikvisionHost", "10.0.0.1");
        ReflectionTestUtils.setField(captureService, "hikvisionPort", 443);
        ReflectionTestUtils.setField(captureService, "hikvisionAppKey", "test-key");
        ReflectionTestUtils.setField(captureService, "hikvisionAppSecret", "test-secret");
        ReflectionTestUtils.setField(captureService, "hikvisionTimeout", 15);
        ReflectionTestUtils.setField(captureService, "hikvisionRetryCount", 3);
        ReflectionTestUtils.setField(captureService, "hikvisionWarmupSeconds", 5.0);
        ReflectionTestUtils.setField(captureService, "hikvisionApiPath", "/artemis");
        ReflectionTestUtils.setField(captureService, "screenshotsDir", "./screenshots");
    }

    private CameraConfig cameraConfig() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM001");
        config.setCameraName("危废仓库1");
        config.setArtemisDeviceId("artemis-dev-001");
        return config;
    }

    @Nested
    @DisplayName("buildCommand()")
    class BuildCommand {

        @Test
        @DisplayName("should build correct command with all args")
        void shouldBuildCorrectCommand() throws Exception {
            Method method = CaptureService.class.getDeclaredMethod("buildCommand", CameraConfig.class);
            method.setAccessible(true);

            String[] cmd = (String[]) method.invoke(captureService, cameraConfig());

            assertThat(cmd[0]).isEqualTo("python3");
            assertThat(cmd[1]).isEqualTo("scripts/camera_capture.py");
            // verify all flags are present
            java.util.List<String> cmdList = java.util.Arrays.asList(cmd);
            assertThat(cmdList).contains("--host", "10.0.0.1");
            assertThat(cmdList).contains("--port", "443");
            assertThat(cmdList).contains("--app-key", "test-key");
            assertThat(cmdList).contains("--app-secret", "test-secret");
            assertThat(cmdList).contains("--camera-code", "CAM001");
            assertThat(cmdList).contains("--camera-name", "危废仓库1");
            assertThat(cmdList).contains("--save-dir", "./screenshots");
            assertThat(cmdList).contains("--timeout", "15");
            assertThat(cmdList).contains("--retry", "3");
            assertThat(cmdList).contains("--warmup", "5.0");
            assertThat(cmdList).contains("--json");
        }

        @Test
        @DisplayName("should not include --device-id when null")
        void shouldNotIncludeDeviceIdWhenNull() throws Exception {
            Method method = CaptureService.class.getDeclaredMethod("buildCommand", CameraConfig.class);
            method.setAccessible(true);
            CameraConfig c = cameraConfig();
            c.setArtemisDeviceId(null);

            String[] cmd = (String[]) method.invoke(captureService, c);
            java.util.List<String> cmdList = java.util.Arrays.asList(cmd);

            assertThat(cmdList).doesNotContain("--device-id");
        }
    }

    @Nested
    @DisplayName("parseResult()")
    class ParseResult {

        @Test
        @DisplayName("should parse online result from JSON")
        void shouldParseOnlineResult() throws Exception {
            Method method = CaptureService.class.getDeclaredMethod("parseResult", String.class);
            method.setAccessible(true);

            String json = "{\"status\":\"online\",\"qualityScore\":0.82,\"screenshotPath\":\"/tmp/cam.jpg\","
                    + "\"qualityDetail\":{\"laplacian\":0.75},\"captureTime\":\"2026-07-06 15:01:23\","
                    + "\"retryUsed\":0,\"errorMsg\":null}";

            CameraCaptureResult result = (CameraCaptureResult) method.invoke(captureService, json);

            assertThat(result.getStatus()).isEqualTo("online");
            assertThat(result.getQualityScore()).isEqualTo(0.82);
            assertThat(result.getScreenshotPath()).isEqualTo("/tmp/cam.jpg");
            assertThat(result.getErrorMsg()).isNull();
            assertThat(result.getCaptureTime()).isEqualTo("2026-07-06 15:01:23");
            assertThat(result.getRetryUsed()).isEqualTo(0);
        }

        @Test
        @DisplayName("should parse offline result")
        void shouldParseOfflineResult() throws Exception {
            Method method = CaptureService.class.getDeclaredMethod("parseResult", String.class);
            method.setAccessible(true);

            String json = "{\"status\":\"offline\",\"qualityScore\":null,\"screenshotPath\":null,"
                    + "\"errorMsg\":\"Connection refused\"}";

            CameraCaptureResult result = (CameraCaptureResult) method.invoke(captureService, json);

            assertThat(result.getStatus()).isEqualTo("offline");
            assertThat(result.getQualityScore()).isNull();
            assertThat(result.getScreenshotPath()).isNull();
            assertThat(result.getErrorMsg()).isEqualTo("Connection refused");
        }

        @Test
        @DisplayName("should throw on invalid JSON")
        void shouldThrowOnInvalidJson() throws Exception {
            Method method = CaptureService.class.getDeclaredMethod("parseResult", String.class);
            method.setAccessible(true);

            assertThatThrownBy(() -> method.invoke(captureService, "not valid json"))
                    .getCause().isInstanceOf(RuntimeException.class);
        }
    }
}
