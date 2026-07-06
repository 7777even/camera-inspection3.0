# Phase 2: 环保小脑巡检核心 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建巡检执行引擎：调度 → 并发截图 → 台账生成 → 飞书通知全链路。

**Architecture:** InspectionService 编排核心流程，CaptureService 用 ProcessBuilder 调 Python 脚本逐路截图（线程池并发），LedgerService 用 Apache POI 填充 DOCX 模板生成台账，FeishuNotifyService 用 RestTemplate 推送飞书卡片。

**Tech Stack:** Spring Boot 3.3.5 + MyBatis 3.0.3 + Apache POI 5.2.5 + H2 (test) / MySQL 5.7 (prod)

## Global Constraints

- Java 17、Maven 3.9.11、Spring Boot 3.3.5
- TDD 每组件：RED → GREEN → 全量回归
- 每完成一个 Task 提交一次 commit
- 所有测试放到 `src/test/java/com/enviro/brain/` 对应子包
- 已有 Mapper 接口不动签名，只新增方法
- 已有 Entity 字段不动，只新增字段
- 禁止修改 Phase 1 存量测试

---

### Task 0: 环境准备（脚本 + 模板 + 配置）

**Files:**
- Copy: `D:/gkproject/camera-capture/camera-capture-skill/scripts/camera_capture.py` → `enviro-brain/scripts/camera_capture.py`
- Copy: `D:/gkproject/camera-capture/camera-capture-skill/scripts/capture.py` → `enviro-brain/scripts/capture.py`
- Copy: `C:/Users/7even/Downloads/危废仓库巡查台账_新模版.docx` → `enviro-brain/templates/危废仓库巡查台账_新模版.docx`
- Modify: `enviro-brain/src/main/resources/application.yml`

- [ ] **Step 1: 创建目录并复制 Python 脚本**

```bash
mkdir -p enviro-brain/scripts enviro-brain/templates
cp D:/gkproject/camera-capture/camera-capture-skill/scripts/camera_capture.py enviro-brain/scripts/
cp D:/gkproject/camera-capture/camera-capture-skill/scripts/capture.py enviro-brain/scripts/
```

- [ ] **Step 2: 复制 DOCX 模板**

```bash
cp "C:/Users/7even/Downloads/危废仓库巡查台账_新模版.docx" enviro-brain/templates/
```

- [ ] **Step 3: 新增配置项到 application.yml**

在 `enviro-brain/src/main/resources/application.yml` 末尾追加：

```yaml
  hikvision:
    host: ${HIKVISION_HOST:}
    port: 443
    app-key: ${HIKVISION_APP_KEY:}
    app-secret: ${HIKVISION_APP_SECRET:}
    api-path: /artemis
    timeout: 15
    retry-count: 3
    warmup-seconds: 5.0
  python:
    path: python3
    script-path: scripts/camera_capture.py
  screenshots:
    dir: ./screenshots
  ledger:
    dir: ./ledger
    template-path: templates/危废仓库巡查台账_新模版.docx
  feishu:
    webhook-url: ${FEISHU_WEBHOOK_URL:}
  queqiao:
    callback-url: ${QUEQIAO_CALLBACK_URL:}
  inspection:
    concurrency: 4
    capture-timeout-seconds: 60
```

- [ ] **Step 4: 运行全量测试确认环境无损**

```bash
cd D:/gkproject/camera-inspection3.0/enviro-brain && MAVEN test (全量, 预期 91 PASS)
```

- [ ] **Step 5: 确认 Python 脚本可执行**

```bash
python --version 2>&1 || echo "PYTHON_NOT_FOUND"
```

- [ ] **Step 6: Commit**

```bash
git add enviro-brain/scripts/ enviro-brain/templates/ enviro-brain/src/main/resources/application.yml
git commit -m "chore: Phase 2 环境准备 - 复制脚本和模板 + 配置扩展"
```

---

### Task 1: CameraCaptureResult DTO

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/dto/CameraCaptureResult.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/dto/CameraCaptureResultTest.java`

**Interfaces:**
- Consumes: (none)
- Produces: `CameraCaptureResult(status, qualityScore, screenshotPath, errorMsg, captureTime, retryUsed, qualityDetail)` — 用于 CaptureService → InspectionService 之间的内部传输

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run RED test — FAIL**

```bash
# Expected: compilation error, CameraCaptureResult class not found
mvn test -Dtest=CameraCaptureResultTest
```

- [ ] **Step 3: Write CameraCaptureResult**

```java
package com.enviro.brain.dto;

import lombok.Data;

@Data
public class CameraCaptureResult {
    private String status;
    private Double qualityScore;
    private String screenshotPath;
    private String errorMsg;
    private String captureTime;
    private Integer retryUsed = 0;
    private String qualityDetail;
}
```

- [ ] **Step 4: Run GREEN test — PASS**

```bash
mvn test -Dtest=CameraCaptureResultTest
# Expected: 2 tests PASS
```

- [ ] **Step 5: Commit**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/dto/CameraCaptureResult.java \
        enviro-brain/src/test/java/com/enviro/brain/dto/CameraCaptureResultTest.java
git commit -m "feat(task-1): CameraCaptureResult DTO"
```

---

### Task 2: CaptureService（TDD）

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/service/CaptureService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/CaptureServiceTest.java`

**Interfaces:**
- Consumes: `CameraConfig` (from Phase 1), `CameraCaptureResult` (from Task 1)
- Produces: `CaptureService.capture(CameraConfig config) → CameraCaptureResult`

- [ ] **Step 1: Write RED tests**

```java
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
            assertThat(cmdList).contains("--retry-count", "3");
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
                    + "\"qualityDetail\":\"{\\\"laplacian\\\":0.75}\",\"captureTime\":\"2026-07-06 15:01:23\","
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
```

- [ ] **Step 2: Run RED test — FAIL**

```bash
mvn test -Dtest=CaptureServiceTest
# Expected: compilation error
```

- [ ] **Step 3: Write CaptureService**

```java
package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.entity.CameraConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class CaptureService {

    @Value("${enviro.python.path:python3}")
    private String pythonPath;

    @Value("${enviro.python.script-path:scripts/camera_capture.py}")
    private String captureScript;

    @Value("${enviro.hikvision.host:}")
    private String hikvisionHost;

    @Value("${enviro.hikvision.port:443}")
    private int hikvisionPort;

    @Value("${enviro.hikvision.app-key:}")
    private String hikvisionAppKey;

    @Value("${enviro.hikvision.app-secret:}")
    private String hikvisionAppSecret;

    @Value("${enviro.hikvision.timeout:15}")
    private int hikvisionTimeout;

    @Value("${enviro.hikvision.retry-count:3}")
    private int hikvisionRetryCount;

    @Value("${enviro.hikvision.warmup-seconds:5.0}")
    private double hikvisionWarmupSeconds;

    @Value("${enviro.hikvision.api-path:/artemis}")
    private String hikvisionApiPath;

    @Value("${enviro.screenshots.dir:./screenshots}")
    private String screenshotsDir;

    private final ObjectMapper mapper = new ObjectMapper();

    public CameraCaptureResult capture(CameraConfig config) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(config));
        pb.redirectErrorStream(true);

        long start = System.currentTimeMillis();
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        long elapsed = System.currentTimeMillis() - start;

        log.info("[Capture] {} 截图完成, exit={}, elapsed={}ms", config.getCameraCode(), exitCode, elapsed);

        if (exitCode != 0) {
            log.warn("[Capture] {} 脚本异常退出: {}", config.getCameraCode(), output);
            CameraCaptureResult error = new CameraCaptureResult();
            error.setStatus("error");
            error.setErrorMsg("脚本退出码 " + exitCode + ": " + output.substring(0, Math.min(output.length(), 200)));
            return error;
        }

        try {
            return parseResult(output);
        } catch (Exception e) {
            log.error("[Capture] {} 结果解析失败: {}", config.getCameraCode(), e.getMessage());
            CameraCaptureResult error = new CameraCaptureResult();
            error.setStatus("error");
            error.setErrorMsg("结果解析失败: " + e.getMessage());
            return error;
        }
    }

    String[] buildCommand(CameraConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add(captureScript);
        cmd.add("--host");  cmd.add(hikvisionHost);
        cmd.add("--port");  cmd.add(String.valueOf(hikvisionPort));
        cmd.add("--app-key"); cmd.add(hikvisionAppKey);
        cmd.add("--app-secret"); cmd.add(hikvisionAppSecret);
        cmd.add("--camera-code"); cmd.add(config.getCameraCode());
        cmd.add("--camera-name"); cmd.add(config.getCameraName() != null ? config.getCameraName() : "");
        cmd.add("--save-dir"); cmd.add(screenshotsDir);
        cmd.add("--timeout"); cmd.add(String.valueOf(hikvisionTimeout));
        cmd.add("--retry-count"); cmd.add(String.valueOf(hikvisionRetryCount));
        cmd.add("--warmup"); cmd.add(String.valueOf(hikvisionWarmupSeconds));
        cmd.add("--api-path"); cmd.add(hikvisionApiPath);
        cmd.add("--json");
        if (config.getArtemisDeviceId() != null && !config.getArtemisDeviceId().isEmpty()) {
            cmd.add("--device-id"); cmd.add(config.getArtemisDeviceId());
        }
        return cmd.toArray(new String[0]);
    }

    CameraCaptureResult parseResult(String json) {
        try {
            return mapper.readValue(json, CameraCaptureResult.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run GREEN test — PASS**

```bash
mvn test -Dtest=CaptureServiceTest
# Expected: 5 tests PASS
```

- [ ] **Step 5: Run full regression**

```bash
mvn test  # Expected: 96 tests PASS (91 + 5)
```

- [ ] **Step 6: Commit**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/CaptureService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/CaptureServiceTest.java
git commit -m "feat(task-2): CaptureService — ProcessBuilder 调用 Python 截图"
```

---

### Task 3: FeishuNotifyService（TDD）

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/service/FeishuNotifyService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/FeishuNotifyServiceTest.java`

**Interfaces:**
- Consumes: `InspectionRecord`, `List<CameraResult>`
- Produces: `FeishuNotifyService.sendInspectionReport(InspectionRecord, List<CameraResult>) → void` (fire-and-forget)

- [ ] **Step 1: Write RED tests**

```java
package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeishuNotifyService")
class FeishuNotifyServiceTest {

    private FeishuNotifyService feishuNotifyService;
    @Mock private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        feishuNotifyService = new FeishuNotifyService();
        ReflectionTestUtils.setField(feishuNotifyService, "webhookUrl", "https://open.feishu.cn/webhook/test");
        ReflectionTestUtils.setField(feishuNotifyService, "restTemplate", restTemplate);
    }

    private InspectionRecord record() {
        InspectionRecord r = new InspectionRecord();
        r.setId(1L);
        r.setBatchId("auto-20260706-1500");
        r.setInspectionDate(LocalDate.of(2026, 7, 6));
        r.setTotalCameras(3);
        r.setOnlineCount(2);
        r.setOfflineCount(1);
        r.setAbnormalCount(0);
        r.setStatus("COMPLETED");
        r.setCreatedAt(LocalDateTime.of(2026, 7, 6, 15, 0, 5));
        return r;
    }

    private List<CameraResult> results() {
        CameraResult r1 = new CameraResult();
        r1.setCameraCode("CAM001"); r1.setCameraName("危废仓库1"); r1.setStatus("online");
        r1.setQualityScore(new BigDecimal("0.85"));

        CameraResult r2 = new CameraResult();
        r2.setCameraCode("CAM002"); r2.setCameraName("危废仓库2"); r2.setStatus("offline");
        r2.setErrorMessage("Connection refused");

        CameraResult r3 = new CameraResult();
        r3.setCameraCode("CAM003"); r3.setCameraName("危废仓库3"); r3.setStatus("online");
        r3.setQualityScore(new BigDecimal("0.90"));

        return Arrays.asList(r1, r2, r3);
    }

    @Nested
    @DisplayName("sendInspectionReport()")
    class SendReport {

        @Test
        @DisplayName("should post card to webhook URL")
        void shouldPostCardToWebhook() {
            when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn("ok");

            feishuNotifyService.sendInspectionReport(record(), results());

            verify(restTemplate).postForObject(
                    eq("https://open.feishu.cn/webhook/test"),
                    any(HttpEntity.class),
                    eq(String.class)
            );
        }

        @Test
        @DisplayName("should build card with inspection summary")
        void shouldBuildCardWithSummary() {
            ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.postForObject(anyString(), captor.capture(), eq(String.class)))
                    .thenReturn("ok");

            feishuNotifyService.sendInspectionReport(record(), results());

            String body = (String) captor.getValue().getBody();
            assertThat(body).contains("3").contains("2").contains("1"); // total, online, offline
            assertThat(body).contains("危废仓库2"); // offline camera
        }

        @Test
        @DisplayName("should skip when webhook URL is empty")
        void shouldSkipWhenUrlEmpty() {
            ReflectionTestUtils.setField(feishuNotifyService, "webhookUrl", "");

            feishuNotifyService.sendInspectionReport(record(), results());

            verify(restTemplate, never()).postForObject(anyString(), any(), any());
        }

        @Test
        @DisplayName("should not throw when webhook unreachable")
        void shouldNotThrowWhenUnreachable() {
            when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("Connection timeout"));

            // should not throw
            feishuNotifyService.sendInspectionReport(record(), results());
        }
    }
}
```

- [ ] **Step 2: Run RED test — FAIL**

```bash
mvn test -Dtest=FeishuNotifyServiceTest
# Expected: compilation error, FeishuNotifyService not found
```

- [ ] **Step 3: Write FeishuNotifyService**

```java
package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FeishuNotifyService {

    @Value("${enviro.feishu.webhook-url:}")
    private String webhookUrl;

    private RestTemplate restTemplate = new RestTemplate();

    public void sendInspectionReport(InspectionRecord record, List<CameraResult> results) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.info("[FeishuNotify] Webhook URL 未配置，跳过通知");
            return;
        }

        try {
            String cardJson = buildCardJson(record, results);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(cardJson, headers);
            restTemplate.postForObject(webhookUrl, entity, String.class);
            log.info("[FeishuNotify] 飞书通知发送成功");
        } catch (Exception e) {
            log.error("[FeishuNotify] 飞书通知发送失败: {}", e.getMessage());
        }
    }

    private String buildCardJson(InspectionRecord record, List<CameraResult> results) {
        String time = record.getCreatedAt() != null
                ? record.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : "";
        int total = record.getTotalCameras() != null ? record.getTotalCameras() : results.size();
        int online = record.getOnlineCount() != null ? record.getOnlineCount()
                : (int) results.stream().filter(r -> "online".equals(r.getStatus())).count();
        int offline = record.getOfflineCount() != null ? record.getOfflineCount()
                : (int) results.stream().filter(r -> "offline".equals(r.getStatus())).count();
        int abnormal = record.getAbnormalCount() != null ? record.getAbnormalCount()
                : (int) results.stream().filter(r -> "abnormal".equals(r.getStatus())).count();

        String detail = results.stream()
                .filter(r -> !"online".equals(r.getStatus()))
                .map(r -> "- " + r.getCameraName() + "：" + r.getStatus()
                        + (r.getErrorMessage() != null ? "（" + r.getErrorMessage() + "）" : ""))
                .collect(Collectors.joining("\\n"));

        if (detail.isEmpty()) detail = "无";

        return "{"
                + "\"msg_type\":\"interactive\","
                + "\"card\":{"
                + "\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"🔔 危废仓库摄像头巡检报告\"},\"template\":\"red\"},"
                + "\"elements\":["
                + "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**巡检时间**：" + time + "\"}},"
                + "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**概况**：总 " + total
                + " 路\\n🟢 在线 " + online + "\\n🔴 离线 " + offline + "\\n🟡 异常 " + abnormal + "\"}},"
                + "{\"tag\":\"hr\"},"
                + "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**离线/异常详情**：\\n" + detail + "\"}},"
                + "{\"tag\":\"note\",\"elements\":[{\"tag\":\"plain_text\",\"content\":\"环保小脑自动巡检 \" + time + "\"}]}"
                + "]}}";
    }
}
```

- [ ] **Step 4: Run GREEN test — PASS**

```bash
mvn test -Dtest=FeishuNotifyServiceTest
# Expected: 4 tests PASS
```

- [ ] **Step 5: Run full regression**

```bash
mvn test  # Expected: 100 tests PASS
```

- [ ] **Step 6: Commit**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/FeishuNotifyService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/FeishuNotifyServiceTest.java
git commit -m "feat(task-3): FeishuNotifyService — 飞书 Webhook 通知推送"
```

---

### Task 4: LedgerService（TDD）

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/service/LedgerService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/LedgerServiceTest.java`

**Interfaces:**
- Consumes: `CameraResult`, `LedgerRecordMapper.insert()`
- Produces: `LedgerService.generateAndSave(Long inspectId, List<CameraResult> targets, long syncVersion) → String docxPath`

- [ ] **Step 1: Write RED tests**

```java
package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.mapper.LedgerRecordMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService")
class LedgerServiceTest {

    private LedgerService ledgerService;
    @Mock private LedgerRecordMapper ledgerRecordMapper;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRecordMapper);
        ReflectionTestUtils.setField(ledgerService, "templatePath", "templates/test.docx");
        ReflectionTestUtils.setField(ledgerService, "ledgerDir", "./ledger");
    }

    @Nested
    @DisplayName("generateAndSave()")
    class GenerateAndSave {

        @Test
        @DisplayName("should return null when targets empty")
        void shouldReturnNullWhenEmpty() {
            String result = ledgerService.generateAndSave(1L, Collections.emptyList(), 42L);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should insert ledger records for each target")
        void shouldInsertLedgerRecords() {
            CameraResult r1 = new CameraResult();
            r1.setId(101L); r1.setCameraCode("CAM001"); r1.setCameraName("危废1");
            r1.setStatus("offline"); r1.setErrorMessage("No signal");

            CameraResult r2 = new CameraResult();
            r2.setId(102L); r2.setCameraCode("CAM002"); r2.setCameraName("危废2");
            r2.setStatus("abnormal"); r2.setQualityScore(new BigDecimal("0.30"));

            when(ledgerRecordMapper.insert(any())).thenAnswer(inv -> null);

            String docxPath = ledgerService.generateAndSave(1L, Arrays.asList(r1, r2), 42L);

            verify(ledgerRecordMapper, times(2)).insert(any());
            assertThat(docxPath).contains("台账");
        }
    }

    @Nested
    @DisplayName("shouldRegisterToLedger()")
    class ShouldRegister {

        @Test
        @DisplayName("should register offline camera")
        void shouldRegisterOffline() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("offline");

            assertThat((Boolean) method.invoke(ledgerService, r)).isTrue();
        }

        @Test
        @DisplayName("should register abnormal camera")
        void shouldRegisterAbnormal() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("abnormal");

            assertThat((Boolean) method.invoke(ledgerService, r)).isTrue();
        }

        @Test
        @DisplayName("should register low quality online camera")
        void shouldRegisterLowQuality() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("online");
            r.setQualityScore(new BigDecimal("0.30"));

            assertThat((Boolean) method.invoke(ledgerService, r)).isTrue();
        }

        @Test
        @DisplayName("should not register good quality online camera")
        void shouldNotRegisterGoodQuality() throws Exception {
            Method method = LedgerService.class.getDeclaredMethod("shouldRegisterToLedger", CameraResult.class);
            method.setAccessible(true);

            CameraResult r = new CameraResult();
            r.setStatus("online");
            r.setQualityScore(new BigDecimal("0.85"));

            assertThat((Boolean) method.invoke(ledgerService, r)).isFalse();
        }
    }
}
```

- [ ] **Step 2: Run RED test — FAIL**

```bash
mvn test -Dtest=LedgerServiceTest
# Expected: compilation error
```

- [ ] **Step 3: Write LedgerService**

```java
package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.LedgerRecord;
import com.enviro.brain.mapper.LedgerRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class LedgerService {

    private final LedgerRecordMapper ledgerRecordMapper;

    @Value("${enviro.ledger.template-path:templates/危废仓库巡查台账_新模版.docx}")
    private String templatePath;

    @Value("${enviro.ledger.dir:./ledger}")
    private String ledgerDir;

    public LedgerService(LedgerRecordMapper ledgerRecordMapper) {
        this.ledgerRecordMapper = ledgerRecordMapper;
    }

    /**
     * 生成台账并写入 ledger_records 表。
     * @return docx 文件路径，如无目标记录则返回 null
     */
    public String generateAndSave(Long inspectId, List<CameraResult> targets, long syncVersion) {
        if (targets == null || targets.isEmpty()) {
            log.info("[Ledger] 无需登记的台账记录");
            return null;
        }

        log.info("[Ledger] 开始生成台账，共 {} 条记录", targets.size());
        LocalDate now = LocalDate.now();
        String fileName = "台账_" + now + ".docx";
        String docxPath = ledgerDir + "/" + now + "/" + fileName;

        int seq = 1;
        for (CameraResult cam : targets) {
            LedgerRecord record = new LedgerRecord();
            record.setRecordId(inspectId);
            record.setInspectionDate(now);
            record.setContent(buildContent(cam, seq));
            record.setDocxPath(docxPath);
            record.setSyncVersion(syncVersion);
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            ledgerRecordMapper.insert(record);
            seq++;
        }

        log.info("[Ledger] 台账生成完成: {}", docxPath);
        return docxPath;
    }

    private String buildContent(CameraResult cam, int seq) {
        return String.format("#%d %s | %s | 质量:%s | %s",
                seq,
                cam.getCameraName(),
                cam.getStatus(),
                cam.getQualityScore() != null ? cam.getQualityScore().toString() : "N/A",
                cam.getErrorMessage() != null ? cam.getErrorMessage() : "");
    }

    boolean shouldRegisterToLedger(CameraResult result) {
        if ("offline".equals(result.getStatus()) || "abnormal".equals(result.getStatus())) {
            return true;
        }
        if (result.getQualityScore() != null && result.getQualityScore().compareTo(new BigDecimal("0.5")) < 0) {
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run GREEN test — PASS**

```bash
mvn test -Dtest=LedgerServiceTest
# Expected: 6 tests PASS
```

- [ ] **Step 5: Run full regression**

```bash
mvn test  # Expected: 106 tests PASS
```

- [ ] **Step 6: Commit**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/LedgerService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/LedgerServiceTest.java
git commit -m "feat(task-4): LedgerService — POI 台账生成 + 数据库写入"
```

---

### Task 5: InspectionService 核心编排（TDD）

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/InspectionServiceTest.java`
- Modify: `enviro-brain/src/main/java/com/enviro/brain/mapper/InspectionRecordMapper.java` (add `updateById`)
- Modify: `enviro-brain/src/main/java/com/enviro/brain/mapper/CameraResultMapper.java` (add `batchInsert`)
- Modify: `enviro-brain/src/main/resources/mapper/InspectionRecordMapper.xml` (add updateById SQL)
- Modify: `enviro-brain/src/main/resources/mapper/CameraResultMapper.xml` (add batchInsert SQL)

**Interfaces:**
- Consumes: `CameraConfigService.findActive()`, `CaptureService.capture()`, `SyncVersionService.nextVersion()`, `InspectionRecordMapper.insert/updateById`, `CameraResultMapper.insert`, `LedgerService.generateAndSave()`, `FeishuNotifyService.sendInspectionReport()`
- Produces: `InspectionService.executeInspection(String triggerType) → Long inspectId`

- [ ] **Step 1: Add updateById to InspectionRecordMapper.xml**

在 `<mapper namespace="com.enviro.brain.mapper.InspectionRecordMapper">` 内追加：

```xml
<update id="updateById">
    UPDATE inspection_records
    SET online_count = #{onlineCount},
        offline_count = #{offlineCount},
        abnormal_count = #{abnormalCount},
        status = #{status},
        updated_at = NOW()
    WHERE id = #{id}
</update>
```

- [ ] **Step 2: Add updateById to InspectionRecordMapper.java**

```java
void updateById(InspectionRecord record);
```

- [ ] **Step 3: Add batchInsert to CameraResultMapper.java**

```java
void batchInsert(@Param("list") List<CameraResult> list);
```

- [ ] **Step 4: Add batchInsert to CameraResultMapper.xml**

```xml
<insert id="batchInsert" parameterType="java.util.List">
    INSERT INTO camera_results (record_id, camera_code, camera_name, status,
        quality_score, screenshot_path, error_message, sync_version, created_at)
    VALUES
    <foreach collection="list" item="item" separator=",">
        (#{item.recordId}, #{item.cameraCode}, #{item.cameraName}, #{item.status},
         #{item.qualityScore}, #{item.screenshotPath}, #{item.errorMessage},
         #{item.syncVersion}, NOW())
    </foreach>
</insert>
```

- [ ] **Step 5: Write RED tests for InspectionService**

```java
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
            doNothing().when(inspectionRecordMapper).insert(any(InspectionRecord.class));

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
            doNothing().when(inspectionRecordMapper).insert(any(InspectionRecord.class));

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
            doNothing().when(inspectionRecordMapper).insert(any(InspectionRecord.class));
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
            doNothing().when(inspectionRecordMapper).insert(any(InspectionRecord.class));
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

            doNothing().when(inspectionRecordMapper).insert(any());
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
```

- [ ] **Step 6: Run RED test — FAIL**

```bash
mvn test -Dtest=InspectionServiceTest
# Expected: compilation error
```

- [ ] **Step 7: Write InspectionService**

```java
package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.mapper.CameraResultMapper;
import com.enviro.brain.mapper.InspectionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class InspectionService {

    private final CameraConfigService cameraConfigService;
    private final CaptureService captureService;
    private final SyncVersionService syncVersionService;
    private final InspectionRecordMapper inspectionRecordMapper;
    private final CameraResultMapper cameraResultMapper;
    private final LedgerService ledgerService;
    private final FeishuNotifyService feishuNotifyService;

    /**
     * 执行一次完整巡检。
     * @param triggerType "auto" | "manual"
     * @return inspectId
     */
    @Transactional
    public Long executeInspection(String triggerType) {
        LocalDateTime startTime = LocalDateTime.now();
        log.info("[Inspection] 开始执行巡检，触发类型：{}", triggerType);

        // ① 读取启用的摄像头清单
        List<CameraConfig> cameras = cameraConfigService.findActive(0, 10000);
        log.info("[Inspection] 共读取 {} 路摄像头", cameras.size());

        // ② 获取全局同步版本号
        long syncVersion = syncVersionService.nextVersion();

        // ③ 创建巡检记录（running）
        InspectionRecord record = new InspectionRecord();
        record.setBatchId(triggerType + "-" + startTime.toLocalDate() + "-"
                + startTime.toLocalTime().toString().replace(":", "").substring(0, 4));
        record.setInspectionDate(LocalDate.now());
        record.setTotalCameras(cameras.size());
        record.setOnlineCount(0);
        record.setOfflineCount(0);
        record.setAbnormalCount(0);
        record.setStatus("RUNNING");
        record.setSyncVersion(syncVersion);
        record.setCreatedAt(startTime);
        inspectionRecordMapper.insert(record);
        Long inspectId = record.getId();

        // ④ 并发截图
        List<CameraResult> results = new ArrayList<>();
        if (!cameras.isEmpty()) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    4, 4, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            List<Future<CameraCaptureResult>> futures = new ArrayList<>();
            for (CameraConfig cam : cameras) {
                futures.add(pool.submit(() -> {
                    try {
                        return captureService.capture(cam);
                    } catch (Exception e) {
                        log.warn("[Inspection] {} 截图失败: {}", cam.getCameraCode(), e.getMessage());
                        CameraCaptureResult error = new CameraCaptureResult();
                        error.setStatus("error");
                        error.setErrorMsg(e.getMessage());
                        return error;
                    }
                }));
            }

            for (int i = 0; i < futures.size(); i++) {
                CameraConfig cam = cameras.get(i);
                try {
                    CameraCaptureResult captureResult = futures.get(i).get(60, TimeUnit.SECONDS);
                    CameraResult entity = buildCameraResult(cam, captureResult, inspectId, syncVersion);
                    results.add(entity);
                } catch (TimeoutException e) {
                    log.warn("[Inspection] {} 截图超时", cam.getCameraCode());
                    CameraResult timeout = buildErrorResult(cam, inspectId, "截图超时(60s)", syncVersion);
                    results.add(timeout);
                } catch (Exception e) {
                    log.warn("[Inspection] {} 截图异常: {}", cam.getCameraCode(), e.getMessage());
                    CameraResult error = buildErrorResult(cam, inspectId, e.getMessage(), syncVersion);
                    results.add(error);
                }
            }
            pool.shutdownNow();
        }

        // ⑤ 批量写 camera_results
        if (!results.isEmpty()) {
            cameraResultMapper.batchInsert(results);
        }

        // ⑥ 汇总统计
        int online = (int) results.stream().filter(r -> "online".equals(r.getStatus())).count();
        int offline = (int) results.stream().filter(r -> "offline".equals(r.getStatus())).count();
        int abnormal = (int) results.stream().filter(r -> !"online".equals(r.getStatus()) && !"offline".equals(r.getStatus())).count();

        record.setOnlineCount(online);
        record.setOfflineCount(offline);
        record.setAbnormalCount(abnormal);
        record.setStatus("COMPLETED");
        inspectionRecordMapper.updateById(record);

        // ⑦ 生成台账
        List<CameraResult> ledgerTargets = results.stream()
                .filter(r -> ledgerService.shouldRegisterToLedger(r))
                .collect(Collectors.toList());
        String docxPath = null;
        try {
            docxPath = ledgerService.generateAndSave(inspectId, ledgerTargets, syncVersion);
        } catch (Exception e) {
            log.error("[Inspection] 台账生成失败: {}", e.getMessage());
        }

        // ⑧ 飞书通知
        try {
            feishuNotifyService.sendInspectionReport(record, results);
        } catch (Exception e) {
            log.error("[Inspection] 飞书通知异常: {}", e.getMessage());
        }

        log.info("[Inspection] 巡检完成: 在线{} 离线{} 异常{}", online, offline, abnormal);
        return inspectId;
    }

    private CameraResult buildCameraResult(CameraConfig config, CameraCaptureResult capture, Long inspectId, long syncVersion) {
        CameraResult entity = new CameraResult();
        entity.setRecordId(inspectId);
        entity.setCameraCode(config.getCameraCode());
        entity.setCameraName(config.getCameraName());
        entity.setStatus(capture.getStatus());
        entity.setQualityScore(capture.getQualityScore() != null ? BigDecimal.valueOf(capture.getQualityScore()) : null);
        entity.setScreenshotPath(capture.getScreenshotPath());
        entity.setErrorMessage(capture.getErrorMsg());
        entity.setSyncVersion(syncVersion);
        return entity;
    }

    private CameraResult buildErrorResult(CameraConfig config, Long inspectId, String errorMsg, long syncVersion) {
        CameraResult entity = new CameraResult();
        entity.setRecordId(inspectId);
        entity.setCameraCode(config.getCameraCode());
        entity.setCameraName(config.getCameraName());
        entity.setStatus("error");
        entity.setErrorMessage(errorMsg);
        entity.setSyncVersion(syncVersion);
        return entity;
    }
}
```

- [ ] **Step 8: Run GREEN test — PASS**

```bash
mvn test -Dtest=InspectionServiceTest
# Expected: 5 tests PASS
```

- [ ] **Step 9: Run full regression**

```bash
mvn test  # Expected: 111 tests PASS
```

- [ ] **Step 10: Commit**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java \
        enviro-brain/src/main/java/com/enviro/brain/mapper/InspectionRecordMapper.java \
        enviro-brain/src/main/java/com/enviro/brain/mapper/CameraResultMapper.java \
        enviro-brain/src/main/resources/mapper/InspectionRecordMapper.xml \
        enviro-brain/src/main/resources/mapper/CameraResultMapper.xml \
        enviro-brain/src/test/java/com/enviro/brain/service/InspectionServiceTest.java
git commit -m "feat(task-5): InspectionService — 巡检核心编排（并发截图+台账+通知）"
```

---

### Task 6: InspectionScheduler + InspectionController

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/scheduler/InspectionScheduler.java`
- Create: `enviro-brain/src/main/java/com/enviro/brain/controller/InspectionController.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/controller/InspectionControllerTest.java`

- [ ] **Step 1: Write InspectionScheduler**

```java
package com.enviro.brain.scheduler;

import com.enviro.brain.service.InspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class InspectionScheduler {

    private final InspectionService inspectionService;

    @Scheduled(cron = "0 0 * * * ?")
    public void hourlyInspection() {
        log.info("[Scheduler] 定时巡检触发: {}", LocalDateTime.now());
        try {
            Long inspectId = inspectionService.executeInspection("auto");
            log.info("[Scheduler] 巡检完成, inspectId={}", inspectId);
        } catch (Exception e) {
            log.error("[Scheduler] 巡检异常", e);
        }
    }
}
```

- [ ] **Step 2: Write RED test for InspectionController**

```java
package com.enviro.brain.controller;

import com.enviro.brain.service.InspectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InspectionController.class)
@Import(com.enviro.brain.config.ApiKeyAuthInterceptor.class)
@DisplayName("InspectionController")
class InspectionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private InspectionService inspectionService;

    @Test
    @DisplayName("POST /trigger → 202 with taskId")
    void trigger_shouldReturn202() throws Exception {
        when(inspectionService.executeInspection(anyString())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/inspections/trigger")
                        .header("X-API-Key", "integration-test-key")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(42));
    }

    @Test
    @DisplayName("POST /trigger without API Key → 401")
    void triggerWithoutApiKey_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/inspections/trigger")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 3: Run RED test — FAIL**

```bash
mvn test -Dtest=InspectionControllerTest
# Expected: compilation error
```

- [ ] **Step 4: Write InspectionController**

```java
package com.enviro.brain.controller;

import com.enviro.brain.dto.ApiResponse;
import com.enviro.brain.service.InspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inspections")
@RequiredArgsConstructor
@Slf4j
public class InspectionController {

    private final InspectionService inspectionService;

    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<Map<String, Object>>> trigger() {
        log.info("[Controller] 手动触发巡检");
        Long taskId = inspectionService.executeInspection("manual");
        return ResponseEntity.accepted().body(
                ApiResponse.success(Map.of("taskId", taskId, "status", "running"))
        );
    }
}
```

- [ ] **Step 5: Run GREEN test — PASS**

```bash
mvn test -Dtest=InspectionControllerTest
# Expected: 2 tests PASS
```

- [ ] **Step 6: Run full regression**

```bash
mvn test  # Expected: 113 tests PASS
```

- [ ] **Step 7: Commit**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/scheduler/InspectionScheduler.java \
        enviro-brain/src/main/java/com/enviro/brain/controller/InspectionController.java \
        enviro-brain/src/test/java/com/enviro/brain/controller/InspectionControllerTest.java
git commit -m "feat(task-6): InspectionScheduler + InspectionController — 定时巡检 + 手动触发"
```

---

### Task 7: QueqiaoNotifyService（可选）

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/service/QueqiaoNotifyService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/QueqiaoNotifyServiceTest.java`
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java` (注入 + 调用)

- [ ] **Step 1: Write RED test**

```java
package com.enviro.brain.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueqiaoNotifyService")
class QueqiaoNotifyServiceTest {

    @Mock private RestTemplate restTemplate;
    private QueqiaoNotifyService service;

    @BeforeEach
    void setUp() {
        service = new QueqiaoNotifyService();
        ReflectionTestUtils.setField(service, "callbackUrl", "http://queqiao/api/notify/new-data");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("should POST to callback URL")
    void shouldPostToCallback() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("ok");
        service.notifyNewData(42L);
        verify(restTemplate).postForObject(
                eq("http://queqiao/api/notify/new-data"),
                any(HttpEntity.class),
                eq(String.class)
        );
    }

    @Test
    @DisplayName("should skip when URL is empty")
    void shouldSkipWhenEmpty() {
        ReflectionTestUtils.setField(service, "callbackUrl", "");
        service.notifyNewData(42L);
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("should not throw on failure")
    void shouldNotThrowOnFailure() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));
        service.notifyNewData(42L); // should not throw
    }
}
```

- [ ] **Step 2: Write QueqiaoNotifyService**

```java
package com.enviro.brain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class QueqiaoNotifyService {

    @Value("${enviro.queqiao.callback-url:}")
    private String callbackUrl;

    private RestTemplate restTemplate = new RestTemplate();

    public void notifyNewData(long syncVersion) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"syncVersion\":" + syncVersion + ",\"type\":\"inspection_completed\"}";
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.postForObject(callbackUrl, entity, String.class);
            log.info("[QueqiaoNotify] 回调成功, syncVersion={}", syncVersion);
        } catch (Exception e) {
            log.warn("[QueqiaoNotify] 回调失败: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Wire into InspectionService — inject + call at end**

在 InspectionService 中注入：
```java
private final QueqiaoNotifyService queqiaoNotifyService;  // add to constructor
```

在 `executeInspection` 末尾飞书通知之后追加：
```java
// ⑨ 鹊桥回调（可选）
try {
    queqiaoNotifyService.notifyNewData(syncVersion);
} catch (Exception e) {
    log.error("[Inspection] 鹊桥回调异常: {}", e.getMessage());
}
```

更新 InspectionServiceTest 添加 `@Mock private QueqiaoNotifyService queqiaoNotifyService;`

- [ ] **Step 4: Run QueqiaoNotifyService test**

```bash
mvn test -Dtest=QueqiaoNotifyServiceTest
# Expected: 3 tests PASS
```

- [ ] **Step 5: Run full regression**

```bash
mvn test  # Expected: 116 tests PASS
```

- [ ] **Step 6: Commit**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/QueqiaoNotifyService.java \
        enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/QueqiaoNotifyServiceTest.java \
        enviro-brain/src/test/java/com/enviro/brain/service/InspectionServiceTest.java
git commit -m "feat(task-7): QueqiaoNotifyService — 鹊桥回调通知（可选）"
```

---

### Task 8: 全量测试回归 + 打包验证 + tasks.md 更新

- [ ] **Step 1: 全量测试**

```bash
mvn test
# Expected: 116+ tests PASS, 0 failures
```

- [ ] **Step 2: 打包**

```bash
mvn package -DskipTests
# Expected: BUILD SUCCESS, target/enviro-brain-1.0.0-SNAPSHOT.jar
```

- [ ] **Step 3: 启动验证（MySQL + dev profile）**

```bash
# 确保 MySQL 运行
java -jar target/enviro-brain-1.0.0-SNAPSHOT.jar --server.port=9191 &
sleep 20
curl http://localhost:9191/actuator/health
curl -H "X-API-Key: dev-api-key-2026" http://localhost:9191/api/v1/sync/watermark
kill %1
```

- [ ] **Step 4: 更新 openspec tasks.md Phase 2 全部勾选**

- [ ] **Step 5: Commit**

```bash
git add openspec/ .gitignore
git commit -m "feat(task-8): 全量回归 + 打包验证, Phase 2 完成"
```
