# Phase 5 链路问题修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Phase 5 完整链路测试发现的 2 个 P1 + 3 个 P2 问题，使完整链路可生产上线。

**Architecture:** 将 trigger 端点异步化（拆分 prepareInspection + runInspectionAsync），修复鹊桥回调认证（X-API-Key + callback-url 默认值），修正 smoke-test.sh 脚本错误。

**Tech Stack:** Spring Boot 3.3.5, Spring AI 1.0.0, JUnit 5 + Mockito, Maven 3.9

## Global Constraints

- 不改变 MCP 工具的参数名（已有调用方依赖）
- 不改变数据库表结构
- 不新增 MCP 工具
- 调度器（InspectionScheduler）行为不变，仍走同步路径
- Java 源代码中使用 ASCII 直引号
- 测试框架：JUnit 5 + Mockito（`@ExtendWith(MockitoExtension.class)`）

---

### Task 1: InspectionContext DTO + InspectionService 拆分

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/dto/InspectionContext.java`
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java`
- Modify: `enviro-brain/src/test/java/com/enviro/brain/service/InspectionServiceTest.java`

**Interfaces:**
- Produces: `InspectionContext` DTO（inspectId, syncVersion, cameras, record）
- Produces: `InspectionService.prepareInspection(String triggerType) → InspectionContext`
- Produces: `InspectionService.runInspectionAsync(InspectionContext ctx) → void`（@Async）
- Produces: `InspectionService.runInspectionBody(InspectionContext ctx) → void`（包级可见）
- Preserves: `InspectionService.executeInspection(String triggerType) → Long`（调度器用，行为不变）

- [ ] **Step 1: 创建 InspectionContext DTO**

```java
package com.enviro.brain.dto;

import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.entity.InspectionRecord;
import lombok.Data;
import java.util.List;

@Data
public class InspectionContext {
    private Long inspectId;
    private long syncVersion;
    private List<CameraConfig> cameras;
    private InspectionRecord record;
}
```

- [ ] **Step 2: 重构 InspectionService — 拆分 prepareInspection + runInspectionBody + runInspectionAsync**

将 `InspectionService.java` 的 `executeInspection` 方法拆为三部分。完整替换文件内容：

```java
package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.dto.InspectionContext;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.mapper.CameraResultMapper;
import com.enviro.brain.mapper.InspectionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
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
    private final QueqiaoNotifyService queqiaoNotifyService;

    @Value("${enviro.inspection.concurrency:12}")
    private int concurrency;

    @Value("${enviro.inspection.capture-timeout-seconds:120}")
    private int captureTimeoutSeconds;

    /**
     * Phase 1: 创建巡检记录（RUNNING），返回上下文。
     * 同步 + @Transactional，由控制器或调度器调用。
     */
    @Transactional
    public InspectionContext prepareInspection(String triggerType) {
        LocalDateTime startTime = LocalDateTime.now();
        log.info("[Inspection] 准备巡检，触发类型：{}", triggerType);

        List<CameraConfig> cameras = cameraConfigService.findActive(1, 10000);
        log.info("[Inspection] 共读取 {} 路摄像头", cameras.size());

        long syncVersion = syncVersionService.nextVersion();

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

        InspectionContext ctx = new InspectionContext();
        ctx.setInspectId(record.getId());
        ctx.setSyncVersion(syncVersion);
        ctx.setCameras(cameras);
        ctx.setRecord(record);
        return ctx;
    }

    /**
     * Phase 2: 执行巡检主体（截图 + 写库 + 台账 + 通知 + 回调）。
     * 无事务注解 — async 路径各 DB 操作自动提交；sync 路径由外层 @Transactional 覆盖。
     */
    void runInspectionBody(InspectionContext ctx) {
        Long inspectId = ctx.getInspectId();
        long syncVersion = ctx.getSyncVersion();
        List<CameraConfig> cameras = ctx.getCameras();
        InspectionRecord record = ctx.getRecord();

        log.info("[Inspection] 开始执行巡检主体, inspectId={}", inspectId);

        // ④ 并发截图
        List<CameraResult> results = new ArrayList<>();
        if (!cameras.isEmpty()) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    concurrency, concurrency, 60, TimeUnit.SECONDS,
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
                    CameraCaptureResult captureResult = futures.get(i).get(captureTimeoutSeconds, TimeUnit.SECONDS);
                    CameraResult entity = buildCameraResult(cam, captureResult, inspectId, syncVersion);
                    results.add(entity);
                } catch (TimeoutException e) {
                    log.warn("[Inspection] {} 截图超时", cam.getCameraCode());
                    String fallbackPath = captureService.findScreenshot(cam.getCameraName());
                    if (fallbackPath != null) {
                        log.info("[Inspection] {} 截图文件已存在: {}，按在线处理", cam.getCameraCode(), fallbackPath);
                        CameraResult online = buildErrorResult(cam, inspectId, null, syncVersion);
                        online.setStatus("online");
                        online.setScreenshotPath(fallbackPath);
                        results.add(online);
                    } else {
                        CameraResult offline = buildErrorResult(cam, inspectId, "截图超时(" + captureTimeoutSeconds + "s)", syncVersion);
                        offline.setStatus("offline");
                        results.add(offline);
                    }
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
        Set<String> ledgerCodes = cameras.stream()
                .filter(c -> c.getLedgerEnabled() != null && c.getLedgerEnabled() == 1)
                .map(CameraConfig::getCameraCode)
                .collect(Collectors.toSet());
        List<CameraResult> ledgerTargets = results.stream()
                .filter(r -> ledgerCodes.contains(r.getCameraCode()))
                .collect(Collectors.toList());
        try {
            ledgerService.generateAndSave(inspectId, ledgerTargets, syncVersion);
        } catch (Exception e) {
            log.error("[Inspection] 台账生成失败: {}", e.getMessage());
        }

        // ⑧ 飞书通知
        try {
            feishuNotifyService.sendInspectionReport(record, results);
        } catch (Exception e) {
            log.error("[Inspection] 飞书通知异常: {}", e.getMessage());
        }

        // ⑨ 鹊桥回调
        try {
            queqiaoNotifyService.notifyNewData(syncVersion);
        } catch (Exception e) {
            log.error("[Inspection] 鹊桥回调异常: {}", e.getMessage());
        }

        log.info("[Inspection] 巡检完成: 在线{} 离线{} 异常{}", online, offline, abnormal);
    }

    /**
     * 异步执行巡检主体（控制器用）。
     * @Async 使该方法在独立线程中执行，调用方立即返回。
     */
    @Async
    public void runInspectionAsync(InspectionContext ctx) {
        runInspectionBody(ctx);
    }

    /**
     * 同步执行完整巡检（调度器用，行为不变）。
     * @Transactional 覆盖 prepare + body，整体一个事务。
     */
    @Transactional
    public Long executeInspection(String triggerType) {
        InspectionContext ctx = prepareInspection(triggerType);
        runInspectionBody(ctx);
        return ctx.getInspectId();
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

- [ ] **Step 3: 更新 InspectionServiceTest — 验证 prepareInspection + executeInspection 仍通过**

在 `InspectionServiceTest.java` 的 `ExecuteInspection` 内部类末尾追加两个测试：

```java
        @Nested
        @DisplayName("prepareInspection()")
        class PrepareInspection {

            @Test
            @DisplayName("should create RUNNING record and return context")
            void shouldCreateRunningRecord() {
                when(cameraConfigService.findActive(anyInt(), anyInt())).thenReturn(cameras());
                when(syncVersionService.nextVersion()).thenReturn(42L);
                doAnswer(invocation -> {
                    InspectionRecord rec = invocation.getArgument(0);
                    rec.setId(1L);
                    return null;
                }).when(inspectionRecordMapper).insert(any(InspectionRecord.class));

                InspectionContext ctx = inspectionService.prepareInspection("manual");

                assertThat(ctx).isNotNull();
                assertThat(ctx.getInspectId()).isEqualTo(1L);
                assertThat(ctx.getSyncVersion()).isEqualTo(42L);
                assertThat(ctx.getCameras()).hasSize(2);
                assertThat(ctx.getRecord().getStatus()).isEqualTo("RUNNING");
                verify(inspectionRecordMapper).insert(any(InspectionRecord.class));
                verify(inspectionRecordMapper, never()).updateById(any());
            }
        }
```

在文件顶部添加 import：

```java
import com.enviro.brain.dto.InspectionContext;
```

- [ ] **Step 4: 运行测试验证**

Run: `cd enviro-brain && mvn test -pl . -Dtest=InspectionServiceTest -Dsurefire.useFile=false`
Expected: 所有测试 PASS（原有 5 个 + 新增 1 个）

- [ ] **Step 5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/dto/InspectionContext.java
git add enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java
git add enviro-brain/src/test/java/com/enviro/brain/service/InspectionServiceTest.java
git commit -m "fix(phase5): 拆分 InspectionService 为 prepareInspection + runInspectionAsync（P1-1）"
```

---

### Task 2: InspectionController 异步化 + @EnableAsync

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/controller/InspectionController.java`
- Modify: `enviro-brain/src/main/java/com/enviro/brain/EnviroBrainApplication.java`
- Modify: `enviro-brain/src/test/java/com/enviro/brain/controller/InspectionControllerTest.java`

**Interfaces:**
- Consumes: `InspectionService.prepareInspection(String) → InspectionContext`
- Consumes: `InspectionService.runInspectionAsync(InspectionContext) → void`

- [ ] **Step 1: 更新 InspectionControllerTest — 先写期望的测试**

替换 `InspectionControllerTest.java` 全文：

```java
package com.enviro.brain.controller;

import com.enviro.brain.config.WebMvcConfig;
import com.enviro.brain.dto.InspectionContext;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.service.InspectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InspectionController.class)
@Import(WebMvcConfig.class)
@TestPropertySource(properties = "enviro.api.key=integration-test-key")
@DisplayName("InspectionController")
class InspectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InspectionService inspectionService;

    @Test
    @DisplayName("POST /trigger → 202 with taskId (async)")
    void trigger_shouldReturn202Async() throws Exception {
        InspectionContext ctx = new InspectionContext();
        ctx.setInspectId(42L);
        ctx.setSyncVersion(99L);
        ctx.setRecord(new InspectionRecord());
        when(inspectionService.prepareInspection(anyString())).thenReturn(ctx);

        mockMvc.perform(post("/api/v1/inspections/trigger")
                        .header("X-API-Key", "integration-test-key")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value(42))
                .andExpect(jsonPath("$.data.status").value("running"));

        verify(inspectionService).prepareInspection("manual");
        verify(inspectionService).runInspectionAsync(ctx);
        verify(inspectionService, never()).executeInspection(anyString());
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

- [ ] **Step 2: 运行测试验证失败（控制器还没改）**

Run: `cd enviro-brain && mvn test -pl . -Dtest=InspectionControllerTest -Dsurefire.useFile=false`
Expected: FAIL — `trigger_shouldReturn202Async` 失败（控制器仍调 `executeInspection`）

- [ ] **Step 3: 修改 InspectionController — 改用 prepare + async**

替换 `InspectionController.java` 全文：

```java
package com.enviro.brain.controller;

import com.enviro.brain.dto.ApiResponse;
import com.enviro.brain.dto.InspectionContext;
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
        InspectionContext ctx = inspectionService.prepareInspection("manual");
        inspectionService.runInspectionAsync(ctx);
        return ResponseEntity.accepted().body(
                ApiResponse.success(Map.of("taskId", ctx.getInspectId(), "status", "running"))
        );
    }
}
```

- [ ] **Step 4: 添加 @EnableAsync 到启动类**

替换 `EnviroBrainApplication.java` 全文：

```java
package com.enviro.brain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enviro Brain 摄像头巡检系统启动类
 */
@SpringBootApplication
@EnableAsync
public class EnviroBrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnviroBrainApplication.class, args);
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

Run: `cd enviro-brain && mvn test -pl . -Dtest=InspectionControllerTest -Dsurefire.useFile=false`
Expected: 2 个测试 PASS

- [ ] **Step 6: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/controller/InspectionController.java
git add enviro-brain/src/main/java/com/enviro/brain/EnviroBrainApplication.java
git add enviro-brain/src/test/java/com/enviro/brain/controller/InspectionControllerTest.java
git commit -m "fix(phase5): InspectionController 异步化 + @EnableAsync（P1-1）"
```

---

### Task 3: QueqiaoNotifyService 回调认证 + 配置

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/QueqiaoNotifyService.java`
- Modify: `enviro-brain/src/main/resources/application.yml`
- Modify: `.env.template`
- Modify: `enviro-brain/src/test/java/com/enviro/brain/service/QueqiaoNotifyServiceTest.java`

**Interfaces:**
- Consumes: `enviro.queqiao.notify-api-key` 配置项（默认 `queqiao-notify-key-2026`）
- Produces: `QueqiaoNotifyService.notifyNewData(long)` 现在发送 `X-API-Key` 头

- [ ] **Step 1: 更新 QueqiaoNotifyServiceTest — 添加 X-API-Key 验证测试**

在 `QueqiaoNotifyServiceTest.java` 的 `setUp` 方法中添加 notifyApiKey 注入，并新增一个测试。替换全文：

```java
package com.enviro.brain.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        ReflectionTestUtils.setField(service, "notifyApiKey", "queqiao-notify-key-2026");
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
    @DisplayName("should send X-API-Key header")
    void shouldSendApiKeyHeader() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("ok");
        service.notifyNewData(42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(anyString(), captor.capture(), eq(String.class));

        HttpHeaders headers = captor.getValue().getHeaders();
        Assertions.assertEquals("queqiao-notify-key-2026", headers.getFirst("X-API-Key"));
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
        service.notifyNewData(42L);
    }
}
```

- [ ] **Step 2: 运行测试验证失败（X-API-Key 测试）**

Run: `cd enviro-brain && mvn test -pl . -Dtest=QueqiaoNotifyServiceTest -Dsurefire.useFile=false`
Expected: `shouldSendApiKeyHeader` FAIL（服务还没加 X-API-Key）

- [ ] **Step 3: 修改 QueqiaoNotifyService — 添加 X-API-Key 头**

替换 `QueqiaoNotifyService.java` 全文：

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

    @Value("${enviro.queqiao.notify-api-key:queqiao-notify-key-2026}")
    private String notifyApiKey;

    private RestTemplate restTemplate = new RestTemplate();

    public void notifyNewData(long syncVersion) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-Key", notifyApiKey);
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

- [ ] **Step 4: 运行测试验证通过**

Run: `cd enviro-brain && mvn test -pl . -Dtest=QueqiaoNotifyServiceTest -Dsurefire.useFile=false`
Expected: 4 个测试 PASS

- [ ] **Step 5: 更新 application.yml — callback-url + notify-api-key 默认值**

在 `enviro-brain/src/main/resources/application.yml` 中，将 `enviro.queqiao` 部分修改为：

```yaml
  queqiao:
    callback-url: ${QUEQIAO_CALLBACK_URL:http://localhost:8081/api/notify/new-data}
    notify-api-key: ${QUEQIAO_NOTIFY_API_KEY:queqiao-notify-key-2026}
```

- [ ] **Step 6: 更新 .env.template — 补充回调配置项**

在 `.env.template` 末尾追加：

```bash

# 鹊桥回调配置
QUEQIAO_CALLBACK_URL=http://localhost:8081/api/notify/new-data
QUEQIAO_NOTIFY_API_KEY=queqiao-notify-key-2026
```

- [ ] **Step 7: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/QueqiaoNotifyService.java
git add enviro-brain/src/main/resources/application.yml
git add .env.template
git add enviro-brain/src/test/java/com/enviro/brain/service/QueqiaoNotifyServiceTest.java
git commit -m "fix(phase5): QueqiaoNotifyService 回调加 X-API-Key + callback-url 默认值（P1-2 + P2-3）"
```

---

### Task 4: smoke-test.sh 脚本修正

**Files:**
- Modify: `scripts/smoke-test.sh`

**Interfaces:**
- 无外部接口依赖，仅修正脚本内部逻辑

- [ ] **Step 1: 修正 smoke-test.sh**

将 `scripts/smoke-test.sh` 中的以下三处修正：

**修正 1** — `get_inspection_summary` 参数名 `start_date`/`end_date` → `start`/`end`：

```python
# 修正前
            res = await s.call_tool('get_inspection_summary', {
                'start_date': '$(date +%Y-%m-%d)',
                'end_date': '$(date +%Y-%m-%d)'
            })

# 修正后
            res = await s.call_tool('get_inspection_summary', {
                'start': '$(date +%Y-%m-%d)',
                'end': '$(date +%Y-%m-%d)'
            })
```

**修正 2** — notify 端点从 `GET /api/notify?syncVersion=` 改为 `POST /api/notify/new-data`：

```bash
# 修正前
curl -sf "http://localhost:8081/api/notify?syncVersion=$LATEST_SYNC" > /dev/null 2>&1 || true

# 修正后
curl -sf -X POST "http://localhost:8081/api/notify/new-data" \
  -H "X-API-Key: queqiao-notify-key-2026" \
  -H "Content-Type: application/json" \
  -d "{\"syncVersion\": $LATEST_SYNC, \"type\": \"inspection_completed\"}" > /dev/null 2>&1 || true
```

**修正 3** — trigger_inspection 调用中 taskId 提取路径适配异步响应：

```python
# 修正前（trigger 现在立即返回，不需要等待）
            data = json.loads(res.content[0].text)
            print(data.get('data',{}).get('taskId',''))

# 修正后（异步模式下 taskId 在 data.taskId 中）
            data = json.loads(res.content[0].text)
            print(data.get('data',{}).get('taskId',''))
```

（此处 taskId 提取逻辑不变，因为 MCP 工具返回的 JSON 结构没变）

- [ ] **Step 2: 提交**

```bash
git add scripts/smoke-test.sh
git commit -m "fix(phase5): smoke-test.sh 参数名 + notify 端点修正（P2-1 + P2-2）"
```

---

### Task 5: 全量构建 + 回归验证

**Files:** 无（仅执行验证）

- [ ] **Step 1: Maven 全量构建**

Run: `cd enviro-brain && mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行全部单元测试**

Run: `cd enviro-brain && mvn test -Dsurefire.useFile=false`
Expected: 全部 PASS

- [ ] **Step 3: 重启 enviro-brain 服务**

停止当前运行的 enviro-brain 进程，用新构建的 jar 重启：
```bash
# 找到并停止当前进程
# 用新 jar 启动
cd enviro-brain && java -jar target/enviro-brain-*.jar --spring.profiles.active=dev &
```

- [ ] **Step 4: 验证 trigger 异步行为**

```bash
# 触发巡检，应在 1-2 秒内返回
time curl -s -X POST http://localhost:8080/api/v1/inspections/trigger \
  -H "X-API-Key: dev-api-key-2026" \
  -H "Content-Type: application/json" \
  -d '{"reason":"async验证"}'
# 期望：1-2s 内返回 {"code":200,...,"data":{"taskId":N,"status":"running"}}
```

- [ ] **Step 5: 验证自动回调同步**

等待巡检完成后（轮询 DB status=COMPLETED），检查 queqiao synced_inspection_records 是否自动新增（无需手动 POST notify）：

```bash
mysql -uroot -proot -h127.0.0.1 -P3306 -e "
SELECT id, status FROM enviro_brain.inspection_records ORDER BY id DESC LIMIT 1;
SELECT id, sync_version, synced_at FROM queqiao_sync.synced_inspection_records ORDER BY id DESC LIMIT 1;
"
# 期望：enviro_brain 有新 COMPLETED 记录，queqiao_sync 也有对应同步记录（synced_at 在巡检完成后几秒内）
```

- [ ] **Step 6: 执行修正后的 smoke-test.sh**

```bash
bash scripts/smoke-test.sh
```
Expected: 全部 PASS

- [ ] **Step 7: 最终提交**

```bash
git add -A
git commit -m "chore(phase5): 链路问题修复完成 — 全量构建 + 回归验证通过"
```
