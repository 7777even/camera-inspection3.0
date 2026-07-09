# MinIO 截图按小时留痕 + 有效期自动清理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每小时的摄像头截图在 MinIO 上成为独立对象（不再跨小时覆盖），并设可配置有效期（默认 7 天），由应用内定时任务自动清理过期对象。

**Architecture:** 修改 `MinioStorageService` 的对象键生成逻辑（加 `_HH`），把"时间戳解析 / 过期判定 / 过期选择"抽成纯函数便于单测；新增 `MinioCleanupScheduler`（`@Scheduled` + 启动 catch-up）调用 `cleanupExpiredObjects()`。配置项（retention-days / cleanup）走 yml/环境变量，带默认值，无需改表结构或台账逻辑。

**Tech Stack:** Java 17, Spring Boot 3.3.5, MinIO Java Client 8.5.7, JUnit 5 + Mockito（spring-boot-starter-test 自带）, Maven（Windows 用 `mvn -s ci-settings.xml`）。

## Global Constraints

- 保留时长：yml 配置项 `enviro.minio.retention-days`，**默认 7 天**。
- 清理机制：**应用内 `@Scheduled` 定时清理**（非 MinIO 服务端 ILM）。
- 对象键格式：`{prefix}/{yyyy-MM-dd}/{safeName}_{HH}.jpg`（同小时内重复巡检仍覆盖，可接受；解决跨小时误调）。
- 解析失败的对象（旧格式每日键、其他文件）**一律跳过，绝不误删**。
- 不改变台账 docx 生成逻辑、不改变 `inspection_records` 表结构、`screenshot_path` 仍存唯一 URL。
- `safeName` 清洗规则不变：仅剔除 `\/ : * ? " < > | % #`、控制字符（`\x00-\x1f`）与空格，保留中文等多语言字符。

---

## File Structure

| 文件 | 职责 |
|------|------|
| `enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java` | 改 key 生成（加 `_HH`）；新增纯逻辑方法 `buildObjectKey/time/parseTimestampFromKey/isExpired/selectExpiredKeys` 与 IO 方法 `listObjectKeys/deleteObjects/cleanupExpiredObjects`；接入 `retentionDays`/`cleanupEnabled` 配置字段 |
| `enviro-brain/src/main/java/com/enviro/brain/scheduler/MinioCleanupScheduler.java` | 新增：`@Component`；`@Scheduled(cron)` 每日清理；`@EventListener(ApplicationReadyEvent)` 启动 catch-up |
| `enviro-brain/src/main/java/com/enviro/brain/config/MinioConfig.java` | 加 `@EnableScheduling`（让 `@Scheduled` 生效） |
| `enviro-brain/src/main/resources/application.yml` | 加 `enviro.minio.retention-days` / `enviro.minio.cleanup` |
| `enviro-brain/src/main/resources/application-prod.yml` | 同上 |
| `enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java` | 新增：纯逻辑单测 + Mockito 验证 delete/cleanup 编排 |
| `enviro-brain/src/test/java/com/enviro/brain/scheduler/MinioCleanupSchedulerTest.java` | 新增（可选）：验证 enabled=false 时整体不执行 |

---

### Task 1: 纯逻辑方法（key 构建 / 时间戳解析 / 过期判定 / 过期选择）— TDD

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java`

**Interfaces:**
- Consumes: 现有 `minioClient` / `endpoint` / `bucket` / `prefix` 字段（构造器暂不改动，本任务不引入配置字段）
- Produces（后续任务直接调用的方法签名，必须保持一致）:
  - `String buildObjectKey(String cameraName, LocalDateTime time)`
  - `Optional<LocalDateTime> parseTimestampFromKey(String key)`
  - `boolean isExpired(String key, LocalDateTime now, int retentionDays)`
  - `List<String> selectExpiredKeys(Collection<String> keys, LocalDateTime now, int retentionDays)`

- [ ] **Step1: 写失败测试**

```java
package com.enviro.brain.service;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    @Mock MinioClient minioClient;
    MinioStorageService service;

    @BeforeEach
    void setup() {
        // 构造器本任务仍用现有 4 参签名（prefix 传空串）
        service = new MinioStorageService(minioClient, "http://minio:9000", "bucket", "", 7, true);
    }

    @Test
    void buildObjectKey_includesHour_noPrefix() {
        String key = service.buildObjectKey("三菱化学危废仓库1", LocalDateTime.of(2026, 7, 9, 14, 5));
        assertThat(key).isEqualTo("2026-07-09/三菱化学危废仓库1_14.jpg");
    }

    @Test
    void buildObjectKey_includesHour_withPrefix() {
        MinioStorageService s2 = new MinioStorageService(minioClient, "http://x", "b", "cam", 7, true);
        String key = s2.buildObjectKey("CAM-001", LocalDateTime.of(2026, 7, 9, 9, 0));
        assertThat(key).isEqualTo("cam/2026-07-09/CAM-001_09.jpg");
    }

    @Test
    void parseTimestamp_valid() {
        Optional<LocalDateTime> ts = service.parseTimestampFromKey("2026-07-09/三菱化学危废仓库1_14.jpg");
        assertThat(ts).isPresent();
        assertThat(ts.get()).isEqualTo(LocalDateTime.of(2026, 7, 9, 14, 0));
    }

    @Test
    void parseTimestamp_invalid_returnsEmpty() {
        assertThat(service.parseTimestampFromKey("2026-07-09/oldname.jpg")).isEmpty();
        assertThat(service.parseTimestampFromKey("garbage")).isEmpty();
        assertThat(service.parseTimestampFromKey(null)).isEmpty();
    }

    @Test
    void isExpired_boundaries() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 12, 0);
        assertThat(service.isExpired("2026-07-01/a_12.jpg", now, 7)).isTrue();   // 8 天前 -> 过期
        assertThat(service.isExpired("2026-07-02/a_12.jpg", now, 7)).isFalse();  // 正好 7 天前 -> 不过期（>7 才删）
        assertThat(service.isExpired("2026-07-09/a_12.jpg", now, 7)).isFalse();  // 当天 -> 不过期
        assertThat(service.isExpired("2026-07-09/oldname.jpg", now, 7)).isFalse(); // 解析失败 -> 安全跳过
    }

    @Test
    void selectExpiredKeys_mixed() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 12, 0);
        List<String> keys = List.of(
                "2026-07-01/a_12.jpg",   // 过期
                "2026-07-09/b_12.jpg",   // 不过期
                "2026-07-09/oldname.jpg", // 解析失败，跳过
                "2026-07-02/c_12.jpg");  // 边界不过期
        assertThat(service.selectExpiredKeys(keys, now, 7)).containsExactly("2026-07-01/a_12.jpg");
    }
}
```

- [ ] **Step2: 运行测试确认失败**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml test -Dtest=MinioStorageServiceTest`
Expected: 编译失败（`buildObjectKey` / `parseTimestampFromKey` 等方法未定义）

- [ ] **Step3: 写最小实现（在 MinioStorageService.java 增加以下方法 + 字段/import）**

在类顶部新增 import：
```java
import io.minio.ListObjectsArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```
（注：原文件已有 `import java.time.LocalDate;` 与 `java.time.format.DateTimeFormatter;`，重复 import 会编译错——若已存在则不重复加 `DateTimeFormatter`。）

在类中新增：
```java
private static final Pattern KEY_PATTERN =
        Pattern.compile("(\\d{4}-\\d{2}-\\d{2}).*_(\\d{2})\\.jpg$");

/** 生成对象键：{prefix}/{yyyy-MM-dd}/{safeName}_{HH}.jpg（HH 取自 time）。time 可注入便于测试。 */
public String buildObjectKey(String cameraName, LocalDateTime time) {
    String datePart = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    String hh = time.format(DateTimeFormatter.ofPattern("HH"));
    String safeName = (cameraName == null || cameraName.isBlank())
            ? UUID.randomUUID().toString()
            : cameraName.replaceAll("[\\\\/:*?\"<>|%#\\x00-\\x1f ]", "_");
    return (prefix == null || prefix.isBlank())
            ? datePart + "/" + safeName + "_" + hh + ".jpg"
            : prefix + "/" + datePart + "/" + safeName + "_" + hh + ".jpg";
}

/** 从 key 解析"日期+小时"；解析失败返回 empty（调用方据此跳过，绝不误删）。 */
public Optional<LocalDateTime> parseTimestampFromKey(String key) {
    if (key == null) return Optional.empty();
    Matcher m = KEY_PATTERN.matcher(key);
    if (!m.find()) return Optional.empty();
    try {
        LocalDate d = LocalDate.parse(m.group(1));
        int hh = Integer.parseInt(m.group(2));
        return Optional.of(LocalDateTime.of(d, LocalTime.of(hh, 0)));
    } catch (Exception e) {
        return Optional.empty();
    }
}

/** 对象是否过期：年龄(天) > retentionDays 才视为过期。key 解析失败 -> false（安全跳过）。 */
public boolean isExpired(String key, LocalDateTime now, int retentionDays) {
    return parseTimestampFromKey(key)
            .map(ts -> ChronoUnit.DAYS.between(ts, now) > retentionDays)
            .orElse(false);
}

/** 纯函数：从给定 key 列表选出过期的（不改 IO）。 */
public List<String> selectExpiredKeys(Collection<String> keys, LocalDateTime now, int retentionDays) {
    List<String> out = new ArrayList<>();
    if (keys == null) return out;
    for (String k : keys) {
        if (isExpired(k, now, retentionDays)) out.add(k);
    }
    return out;
}
```
（本任务暂不引入 `retentionDays`/`cleanupEnabled` 字段，测试用现有 4 参构造器并临时补两个参数；见 Task 2 统一改造构造器。若 4 参构造器不存在，先按现有签名补 `int retentionDays, boolean cleanupEnabled` 两个参数占位，Task 2 再接 `@Value`。）

- [ ] **Step4: 运行测试确认通过**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml test -Dtest=MinioStorageServiceTest`
Expected: 6 个测试全部 PASS

- [ ] **Step5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java
git commit -m "feat(minio): 新增 key 构建/时间解析/过期判定纯逻辑方法(含单测)"
```

---

### Task 2: 改造上传 key + 接入 retention/cleanup 配置字段

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 1 的 `buildObjectKey(String, LocalDateTime)`
- Produces: 构造器签名变更为
  `MinioStorageService(MinioClient, @Value endpoint, @Value bucket, @Value prefix, @Value("${enviro.minio.retention-days:7}") int retentionDays, @Value("${enviro.minio.cleanup.enabled:true}") boolean cleanupEnabled)`
  后续 Task 3 的 `cleanupExpiredObjects()` 依赖这两个字段。

- [ ] **Step1: 写失败测试（验证 uploadScreenshot 用含 _HH 的 key 上传）**

在 `MinioStorageServiceTest` 追加：
```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;
import io.minio.PutObjectArgs;

@Test
void uploadScreenshot_usesHourlyKey() throws Exception {
    byte[] img = new byte[]{1, 2, 3};
    // 固定时间由 buildObjectKey 决定；uploadScreenshot 内部用 LocalDateTime.now()
    // 这里只验证 putObject 被调用且 object 名符合含 _HH 的模式
    service.uploadScreenshot("三菱化学危废仓库1", img);
    ArgumentCaptor<PutObjectArgs> cap = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(minioClient).putObject(cap.capture());
    String objName = cap.getValue().object();
    assertThat(objName).matches(".*/三菱化学危废仓库1_\\d{2}\\.jpg$");
}
```

- [ ] **Step2: 运行测试确认失败**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml test -Dtest=MinioStorageServiceTest`
Expected: FAIL（`uploadScreenshot` 仍用旧 key 且无 retentionDays 字段，编译/断言失败）

- [ ] **Step3: 改造上传 key + 构造器接配置**

3a. 在字段区新增：
```java
private final int retentionDays;
private final boolean cleanupEnabled;
```

3b. 替换构造器签名与赋值（在现有 4 参基础上加两参）：
```java
public MinioStorageService(MinioClient minioClient,
                           @Value("${enviro.minio.endpoint}") String endpoint,
                           @Value("${enviro.minio.bucket}") String bucket,
                           @Value("${enviro.minio.prefix}") String prefix,
                           @Value("${enviro.minio.retention-days:7}") int retentionDays,
                           @Value("${enviro.minio.cleanup.enabled:true}") boolean cleanupEnabled) {
    this.minioClient = minioClient;
    this.endpoint = endpoint;
    this.bucket = bucket;
    this.prefix = prefix;
    this.retentionDays = retentionDays;
    this.cleanupEnabled = cleanupEnabled;
}
```
（同时把 Task 1 里临时占位的两个参数去掉，统一为上述 `@Value` 注入。）

3c. 在 `uploadScreenshot` 中，将原来拼 `objectKey` 的代码（约第 53-60 行：先算 `datePart`+`safeName` 再拼）整段替换为：
```java
String objectKey = buildObjectKey(cameraName, LocalDateTime.now());
```
`buildObjectKey` 内部已处理 `safeName` 清洗与 `prefix`，故删除原 `datePart`/`safeName` 局部变量即可。

- [ ] **Step4: 运行测试确认通过**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml test -Dtest=MinioStorageServiceTest`
Expected: 全部 PASS

- [ ] **Step5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java
git commit -m "feat(minio): 上传 key 加 _HH 避免跨小时覆盖; 接入 retention/cleanup 配置"
```

---

### Task 3: 实现 cleanupExpiredObjects（list + delete 编排）

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java`（追加）

**Interfaces:**
- Consumes: Task 1 的 `selectExpiredKeys` / `isExpired`；本任务新增 `listObjectKeys()`（IO）与 `deleteObjects(List<String>)`（IO）
- Produces: `int cleanupExpiredObjects()` —— Task 4 的 `MinioCleanupScheduler` 调用它

- [ ] **Step1: 写失败测试（IoC 编排：禁用不删 / 启用只删过期）**

在 `MinioStorageServiceTest` 追加：
```java
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import io.minio.RemoveObjectsArgs;
import io.minio.messages.DeleteObject;
import java.util.List;

@Test
void cleanupExpiredObjects_skipsWhenDisabled() throws Exception {
    MinioStorageService disabled = new MinioStorageService(minioClient, "http://x", "b", "", 7, false);
    int n = disabled.cleanupExpiredObjects();
    assertThat(n).isEqualTo(0);
    verify(minioClient, never()).removeObjects(any());
}

@Test
void cleanupExpiredObjects_deletesOnlyExpired() throws Exception {
    MinioStorageService spy = spy(service);
    doReturn(List.of("2026-07-01/a_12.jpg", "2026-07-09/b_12.jpg")).when(spy).listObjectKeys();
    int n = spy.cleanupExpiredObjects();
    assertThat(n).isEqualTo(1);
    ArgumentCaptor<RemoveObjectsArgs> cap = ArgumentCaptor.forClass(RemoveObjectsArgs.class);
    verify(minioClient).removeObjects(cap.capture());
    List<DeleteObject> objs = cap.getValue().objects();
    assertThat(objs).hasSize(1);
    assertThat(objs.get(0).objectName()).isEqualTo("2026-07-01/a_12.jpg");
}
```
（`service` 在本任务构造器已是 6 参；`@BeforeEach` 中的 `new MinioStorageService(minioClient, "http://minio:9000", "bucket", "", 7, true)` 已对齐，无需改。）

- [ ] **Step2: 运行测试确认失败**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml test -Dtest=MinioStorageServiceTest`
Expected: FAIL（`cleanupExpiredObjects` / `listObjectKeys` 未定义）

- [ ] **Step3: 实现 listObjectKeys / deleteObjects / cleanupExpiredObjects**

在 `MinioStorageService` 中新增：
```java
private String effectivePrefix() {
    return (prefix == null || prefix.isBlank()) ? "" : prefix + "/";
}

/** 列出桶内（按 prefix）全部对象名。IO 方法，供清理编排调用。 */
public List<String> listObjectKeys() throws Exception {
    List<String> keys = new ArrayList<>();
    Iterable<Result<Item>> results = minioClient.listObjects(
            ListObjectsArgs.builder().bucket(bucket).prefix(effectivePrefix()).recursive(true).build());
    for (Result<Item> r : results) {
        keys.add(r.get().objectName());
    }
    return keys;
}

/** 批量删除给定 key。IO 方法。 */
public void deleteObjects(List<String> keys) throws Exception {
    if (keys == null || keys.isEmpty()) return;
    List<DeleteObject> objs = keys.stream().map(DeleteObject::new).collect(java.util.stream.Collectors.toList());
    minioClient.removeObjects(RemoveObjectsArgs.builder().bucket(bucket).objects(objs).build());
}

/** 编排：cleanup.enabled=false 直接返回 0；否则列出->选过期->删除，返回删除数量。 */
public int cleanupExpiredObjects() {
    if (!cleanupEnabled) {
        log.info("[Minio] 清理已禁用(cleanup.enabled=false)，跳过");
        return 0;
    }
    try {
        LocalDateTime now = LocalDateTime.now();
        List<String> keys = listObjectKeys();
        List<String> expired = selectExpiredKeys(keys, now, retentionDays);
        if (expired.isEmpty()) {
            log.info("[Minio] 无过期截图需清理");
        } else {
            deleteObjects(expired);
            log.info("[Minio] 清理过期截图 {} 张", expired.size());
        }
        return expired.size();
    } catch (Exception e) {
        log.error("[Minio] 清理过期截图失败: {}", e.getMessage(), e);
        return 0;
    }
}
```

- [ ] **Step4: 运行测试确认通过**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml test -Dtest=MinioStorageServiceTest`
Expected: 全部 PASS

- [ ] **Step5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java
git commit -m "feat(minio): 实现 cleanupExpiredObjects 列出/选择/批量删除过期截图"
```

---

### Task 4: 新增 MinioCleanupScheduler + @EnableScheduling

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/scheduler/MinioCleanupScheduler.java`
- Modify: `enviro-brain/src/main/java/com/enviro/brain/config/MinioConfig.java`

**Interfaces:**
- Consumes: Task 3 的 `MinioStorageService.cleanupExpiredObjects()`
- Produces: 无（自驱动定时任务）

- [ ] **Step1: 写失败编译占位（先创建极简类确认注入）**

创建 `MinioCleanupScheduler.java`：
```java
package com.enviro.brain.scheduler;

import com.enviro.brain.service.MinioStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MinioCleanupScheduler {

    private final MinioStorageService minioStorageService;

    public MinioCleanupScheduler(MinioStorageService minioStorageService) {
        this.minioStorageService = minioStorageService;
    }

    /** 应用启动后先跑一次 catch-up，避免错过调度窗口 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            int n = minioStorageService.cleanupExpiredObjects();
            log.info("[Minio] 启动清理完成: 删除 {} 张", n);
        } catch (Exception e) {
            log.warn("[Minio] 启动清理异常: {}", e.getMessage());
        }
    }

    /** 每日 02:00 清理；cron 可用 yml/env 覆盖，缺省 0 0 2 * * ? */
    @Scheduled(cron = "${enviro.minio.cleanup.cron:0 0 2 * * ?}")
    public void scheduledCleanup() {
        try {
            int n = minioStorageService.cleanupExpiredObjects();
            log.info("[Minio] 定时清理完成: 删除 {} 张", n);
        } catch (Exception e) {
            log.warn("[Minio] 定时清理异常: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step2: 在 MinioConfig 加 @EnableScheduling**

读取 `MinioConfig.java`，在类注解处加（若已有 `@Configuration`）：
```java
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class MinioConfig { ... }
```
（若该类无 `@Configuration`，改为在任意一个 `@SpringBootApplication` 主类或已有配置类上加 `@EnableScheduling`。）

- [ ] **Step3: 编译确认通过**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml compile`
Expected: BUILD SUCCESS

- [ ] **Step4: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/scheduler/MinioCleanupScheduler.java \
        enviro-brain/src/main/java/com/enviro/brain/config/MinioConfig.java
git commit -m "feat(minio): 新增 MinioCleanupScheduler 每日02:00+启动catch-up清理"
```

---

### Task 5: yml 配置（retention-days / cleanup）

**Files:**
- Modify: `enviro-brain/src/main/resources/application.yml`
- Modify: `enviro-brain/src/main/resources/application-prod.yml`

**Interfaces:**
- Consumes: Task 2 的 `@Value` 键 `enviro.minio.retention-days` / `enviro.minio.cleanup.enabled`；Task 4 的 `@Scheduled(cron="${enviro.minio.cleanup.cron:...}")`
- Produces: 无

- [ ] **Step1: 在 application.yml 的 enviro.minio 下补充**

找到现有 `enviro:` → `minio:` 段，追加（保留已有 endpoint/bucket/prefix）：
```yaml
    retention-days: 7
    cleanup:
      enabled: true
      cron: "0 0 2 * * ?"
```
完整示例（仅展示 minio 段，勿改动其他段）：
```yaml
enviro:
  minio:
    endpoint: ${ENVIRO_MINIO_ENDPOINT}
    bucket: ${ENVIRO_MINIO_BUCKET}
    prefix: ${ENVIRO_MINIO_PREFIX:}
    retention-days: 7
    cleanup:
      enabled: true
      cron: "0 0 2 * * ?"
```

- [ ] **Step2: 在 application-prod.yml 做同样补充**

（prod 段若用环境变量注入，可写 `retention-days: ${ENVIRO_MINIO_RETENTION_DAYS:7}` 与 `cleanup.enabled: ${ENVIRO_MINIO_CLEANUP_ENABLED:true}`；cron 同样可用 `${ENVIRO_MINIO_CLEANUP_CRON:0 0 2 * * ?}`。保持与 application.yml 结构一致。）

- [ ] **Step3: 提交**

```bash
git add enviro-brain/src/main/resources/application.yml \
        enviro-brain/src/main/resources/application-prod.yml
git commit -m "feat(minio): yml 增加 retention-days/cleanup 配置(默认7天,每日02:00)"
```

---

### Task 6: 全量构建 + 测试 + 手动验证说明

**Files:**
- 无新增；验证用

**Interfaces:**
- Consumes: Task 1-5 全部产出

- [ ] **Step1: 跑全量测试**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml test`
Expected: 全部测试 PASS（含 MinioStorageServiceTest 的 8 个用例）

- [ ] **Step2: 打包确认编译通过**

Run: `cd enviro-brain && mvn -s ../ci-settings.xml package -DskipTests`
Expected: BUILD SUCCESS，生成 `target/*.jar`

- [ ] **Step3: 手动验证清单（文档化，便于人工在测试/生产跑）**

1. **每小时独立对象**：起服务跑两轮巡检（间隔≥1 小时，或手动触发两次），用 `mc ls` / `scripts/view_pg.py` 或在 MinIO 控制台确认同一摄像头出现两个 key（如 `..._14.jpg` 与 `..._15.jpg`），且 `inspection_records.screenshot_path` 指向各自唯一 URL。
2. **清理触发（安全验证）**：临时把 `enviro.minio.retention-days` 设为 `0` 重启，观察日志 `[Minio] 清理过期截图 N 张`（N 应为已有对象数），确认删除发生；随后**改回 7** 再重启，避免误清。
3. **安全边界**：放入一个旧格式 key（如 `2026-07-09/旧名.jpg` 无 `_HH`）与一两个非图片文件，确认清理日志显示"无过期"或仅删合法过期项，**旧格式/非图片不被删**。
4. **开关**：设 `enviro.minio.cleanup.enabled: false` 重启，确认日志 `[Minio] 清理已禁用...跳过` 且 `removeObjects` 不被调用。

- [ ] **Step4: 提交验证说明（可选，写入 docs）**

若手动验证产出有价值笔记，追加到 `docs/危废仓库巡查台账导出说明.md` 或新建 `docs/minio-screenshot-retention.md`，并提交：
```bash
git add docs/minio-screenshot-retention.md
git commit -m "docs: MinIO 截图有效期手动验证说明"
```

---

## Self-Review

1. **Spec coverage**: §3 key 方案 → Task 2; §4 清理任务 → Task 3+4; §5 配置 → Task 5; §6 影响 → 全程未动 docx/表结构（符合）; §7 测试 → Task 1/3 单测 + Task 6 手动验证。无遗漏。
2. **Placeholder scan**: 无 TBD/TODO；每个代码步骤均给出完整代码；无"适当处理错误"之类空话。
3. **Type consistency**: `buildObjectKey(String, LocalDateTime)` / `parseTimestampFromKey(String)` / `isExpired(String, LocalDateTime, int)` / `selectExpiredKeys(Collection<String>, LocalDateTime, int)` / `cleanupExpiredObjects()` / `listObjectKeys()` / `deleteObjects(List<String>)` 在 Task 1-3 定义与调用处签名一致；构造器 6 参签名在 Task 1（占位）/ Task 2（定稿）统一。
4. **风险点**: Task 3 的 Mockito 验证依赖 `RemoveObjectsArgs.objects()` 与 `DeleteObject.objectName()` 在 MinIO 8.5.7 中存在（确属公开 API）；若实际返回结构不同，实现者按 SDK 调整 getter，测试断言同步调整。
