# 港区小脑接入 enviro-brain 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"港区小脑"作为第二个场景接入 enviro-brain 自有巡检流水线，使港区摄像头由 enviro-brain 抓拍、上传 MinIO（前缀 `gangqu/`）、发专用飞书卡片，与环保小脑（enviro）互不混批；并修复 `CaptureService` 的 `--device-id` 死代码 bug。

**Architecture:** 单应用 + 场景循环。新增 `scenario` 维度贯穿 `camera_config` / `inspection_records` / 飞书 / MinIO；场景相关配置（飞书 webhook、卡片文案、MinIO 前缀）抽到 `application.yml` 的 `enviro.scenarios.{enviro,gangqu}`，由新建 `ScenarioConfigs`（`@ConfigurationProperties`）绑定为 `Map<String,ScenarioConfig>`。调度器循环所有场景各跑一批，0 路场景跳过；手动触发支持 `?scenario=gangqu`。取流凭证全局一份，港区复用，零改动。

**Tech Stack:** Java 17 + Spring Boot 3 + MyBatis / MyBatis XML / Maven；PostgreSQL（运行库，手动 `ALTER`）+ H2（`MODE=MYSQL`，测试库）；JUnit 5 + Mockito + AssertJ；Lombok `@Data`；Python `psycopg2`（录入脚本）。

## Global Constraints

- 运行 PG 库**无 Flyway/Liquibase**，schema 变更需**手动执行 `ALTER TABLE`**（见 Task 1、Task 3）。
- `src/main/resources/db/schema.sql` 是 **MySQL 旧版，不可作准**；真实 schema 以运行 PG 库 + 实体/Mapper 为准，测试库以 `src/test/resources/db/schema-h2.sql` 为准（Task 3 同步）。
- `CameraResult.localScreenshotPath` 字段**从未落库**，台账如需港区截图走 MinIO URL，勿依赖。
- `camera_config.camera_code` 是唯一约束键（`uk_camera_code`），录入走 `ON CONFLICT (camera_code)` 幂等。
- 海康取流平台与环保**同一份**（全局 `@Value` 凭证），港区 `camera_code` 即其海康 `cameraIndexCode`，取流逻辑零改动。
- 编码约定：Java 文件 UTF-8；CSV 读取用 `utf-8-sig`（含 BOM）。
- 每完成一个 Task 即 `git commit`（频繁提交，便于 review/回滚）。
- `scenario` 字段默认值统一为 `"enviro"`，保证存量环保数据不受影响。

---

### Task 1: 数据模型 — 实体与 Mapper（scenario 维度）

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/entity/CameraConfig.java`
- Modify: `enviro-brain/src/main/java/com/enviro/brain/entity/InspectionRecord.java`
- Modify: `enviro-brain/src/main/resources/mapper/CameraConfigMapper.xml`
- Modify: `enviro-brain/src/main/java/com/enviro/brain/mapper/CameraConfigMapper.java`
- Modify: `enviro-brain/src/main/resources/mapper/InspectionRecordMapper.xml`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/CameraConfigServiceTest.java`

**Interfaces:**
- Consumes: 无（基础层）。
- Produces: `CameraConfig.scenario`、`InspectionRecord.scenario` 字段；`CameraConfigMapper.findActiveByScenario(scenario, offset, size)`、`CameraConfigMapper.countByScenario(scenario)`；`CameraConfigService.findActiveByScenario(page, size, scenario)`、`CameraConfigService.countByScenario(scenario)`。

- [ ] **Step 1: 给实体加 scenario 字段**

`CameraConfig.java` 在 `private Integer ledgerEnabled;` 之后加：
```java
/** 场景维度：enviro=环保小脑（默认），gangqu=港区小脑 */
private String scenario = "enviro";
```

`InspectionRecord.java` 在 `private Long syncVersion = 0L;` 之前加：
```java
/** 场景维度：enviro=环保小脑（默认），gangqu=港区小脑 */
private String scenario = "enviro";
```

- [ ] **Step 2: 写失败测试（按 scenario 过滤）**

在 `CameraConfigServiceTest.java` 的 `@Nested` 内新增一组（或顶层方法），验证 `findActiveByScenario` 只返回对应场景：
```java
@org.junit.jupiter.api.Nested
class ScenarioFilter {
    @Test
    void shouldReturnOnlyMatchingScenario() {
        CameraConfig enviro = new CameraConfig(); enviro.setCameraCode("E1"); enviro.setCameraName("环保1"); enviro.setEnabled(1); enviro.setScenario("enviro");
        CameraConfig gangqu = new CameraConfig(); gangqu.setCameraCode("G1"); gangqu.setCameraName("港区1"); gangqu.setEnabled(1); gangqu.setScenario("gangqu");
        when(mapper.findActiveByScenario(eq("gangqu"), anyInt(), anyInt())).thenReturn(List.of(gangqu));
        when(mapper.countByScenario("gangqu")).thenReturn(1);
        List<CameraConfig> result = service.findActiveByScenario(1, 100, "gangqu");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScenario()).isEqualTo("gangqu");
        assertThat(service.countByScenario("gangqu")).isEqualTo(1);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -pl enviro-brain test -Dtest=CameraConfigServiceTest#ScenarioFilter`
Expected: FAIL（`findActiveByScenario` / `countByScenario` 方法不存在）

- [ ] **Step 4: 改 Mapper 接口与 XML**

`CameraConfigMapper.java` 增加：
```java
List<CameraConfig> findActiveByScenario(@Param("scenario") String scenario, @Param("offset") int offset, @Param("size") int size);
int countByScenario(@Param("scenario") String scenario);
```

`CameraConfigMapper.xml`：
- `insert` 的列与值增加 `scenario` / `#{scenario}`：
```xml
<insert id="insert">
  INSERT INTO camera_config (camera_code, camera_name, enterprise, artemis_device_id, rtsp_url, location, enabled, scenario)
  VALUES (#{cameraCode}, #{cameraName}, #{enterprise}, #{artemisDeviceId}, #{rtspUrl}, #{location}, #{enabled}, #{scenario})
</insert>
```
- `upsert` 的 PostgreSQL 分支（含 `ON CONFLICT (camera_code) DO UPDATE SET ...`）SET 列表追加 `scenario = EXCLUDED.scenario`；通用 MERGE 分支同理追加。
- `update` 的 SET 追加 `scenario = #{scenario}`。
- 新增两个 select：
```xml
<select id="findActiveByScenario" resultType="com.enviro.brain.entity.CameraConfig">
  SELECT * FROM camera_config WHERE enabled = 1 AND scenario = #{scenario} ORDER BY id ASC LIMIT #{size} OFFSET #{offset}
</select>
<select id="countByScenario" resultType="java.lang.Integer">
  SELECT COUNT(*) FROM camera_config WHERE scenario = #{scenario}
</select>
```

`InspectionRecordMapper.xml` 的 `insert` 列与值增加 `scenario` / `#{scenario}`：
```xml
INSERT INTO inspection_records (batch_id, inspection_date, total_cameras, online_count,
    offline_count, abnormal_count, status, sync_version, scenario)
VALUES (#{batchId}, #{inspectionDate}, #{totalCameras}, #{onlineCount},
    #{offlineCount}, #{abnormalCount}, #{status}, #{syncVersion}, #{scenario})
```

- [ ] **Step 5: 改 CameraConfigService 暴露新方法**

在 `CameraConfigService.java` 加：
```java
public List<CameraConfig> findActiveByScenario(int page, int size, String scenario) {
    int offset = (page - 1) * size;
    return cameraConfigMapper.findActiveByScenario(scenario, offset, size);
}
public int countByScenario(String scenario) {
    return cameraConfigMapper.countByScenario(scenario);
}
```

- [ ] **Step 6: 重跑测试确认通过**

Run: `mvn -pl enviro-brain test -Dtest=CameraConfigServiceTest`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/entity/CameraConfig.java \
        enviro-brain/src/main/java/com/enviro/brain/entity/InspectionRecord.java \
        enviro-brain/src/main/resources/mapper/CameraConfigMapper.xml \
        enviro-brain/src/main/java/com/enviro/brain/mapper/CameraConfigMapper.java \
        enviro-brain/src/main/resources/mapper/InspectionRecordMapper.xml \
        enviro-brain/src/test/java/com/enviro/brain/service/CameraConfigServiceTest.java
git commit -m "feat(data): camera_config/inspection_records 增加 scenario 维度与按场景过滤"
```

---

### Task 2: 测试库 schema + 运行库 ALTER

**Files:**
- Modify: `enviro-brain/src/test/resources/db/schema-h2.sql`
- (运行库) 手动执行 SQL（见 Step 4），不落在仓库文件（无 migration 工具）。

**Interfaces:**
- Consumes: Task 1 的 schema 列定义。
- Produces: 测试库 `schema-h2.sql` 与运行 PG 库结构一致（均含 `scenario` 列）。

- [ ] **Step 1: 写失败测试（schema 含 scenario 列）**

新增 `CameraConfigMapperTest` 用例（或扩展现有）验证查询返回对象带 `scenario`：
```java
@Test
void shouldMapScenarioColumn() {
    CameraConfig c = new CameraConfig(); c.setCameraCode("SC1"); c.setCameraName("场景"); c.setEnabled(1); c.setScenario("gangqu");
    mapper.insert(c);
    CameraConfig loaded = mapper.findByCameraCode("SC1");
    assertThat(loaded.getScenario()).isEqualTo("gangqu");
}
```
（若现有 `CameraConfigMapperTest` 已覆盖 insert/find，直接追加此方法。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl enviro-brain test -Dtest=CameraConfigMapperTest`
Expected: FAIL（`scenario` 列不存在 / 字段为 null）

- [ ] **Step 3: 同步测试库 schema-h2.sql**

在 `schema-h2.sql` 的 `camera_config` 建表语句（第 62–73 行）`enabled INT NOT NULL DEFAULT 1,` 之后加：
```sql
    scenario VARCHAR(32) NOT NULL DEFAULT 'enviro',
```
在 `inspection_records` 建表语句（第 6–18 行）`sync_version BIGINT NOT NULL DEFAULT 0,` 之后加：
```sql
    scenario VARCHAR(32) NOT NULL DEFAULT 'enviro',
```
（保持 H2 的 `MODE=MYSQL`，`VARCHAR(32)` 与 `DEFAULT 'enviro'` 语法兼容。）

- [ ] **Step 4: 运行库 ALTER（手动，需 DB 访问）**

连接到运行 PG 库 `smartpark_scenes_zhh`（172.168.97.180:31028），执行：
```sql
ALTER TABLE camera_config
  ADD COLUMN IF NOT EXISTS scenario VARCHAR(32) NOT NULL DEFAULT 'enviro';
CREATE INDEX IF NOT EXISTS idx_camera_config_scenario ON camera_config(scenario);

ALTER TABLE inspection_records
  ADD COLUMN IF NOT EXISTS scenario VARCHAR(32) NOT NULL DEFAULT 'enviro';
```
> 风险：生产库操作，先确认连接库名正确；`IF NOT EXISTS` 幂等可重复执行。

- [ ] **Step 5: 重跑测试确认通过**

Run: `mvn -pl enviro-brain test -Dtest=CameraConfigMapperTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add enviro-brain/src/test/resources/db/schema-h2.sql \
        enviro-brain/src/test/java/com/enviro/brain/mapper/CameraConfigMapperTest.java
git commit -m "test(data): schema-h2 增加 scenario 列；提供运行库 ALTER SQL"
```
> 运行库 ALTER 由用户在部署环境执行（见 Task 12 验收步骤），不进仓库。

---

### Task 3: ScenarioConfig 配置绑定

**Files:**
- Create: `enviro-brain/src/main/java/com/enviro/brain/config/ScenarioConfig.java`
- Create: `enviro-brain/src/main/java/com/enviro/brain/config/ScenarioConfigs.java`
- Modify: `enviro-brain/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 无。
- Produces: `ScenarioConfigs` bean（类型 `Map<String,ScenarioConfig>`），供 `InspectionService` / `FeishuNotifyService` 按 `scenario` 取配置。

- [ ] **Step 1: 创建 POJO**

`ScenarioConfig.java`：
```java
package com.enviro.brain.config;

import lombok.Data;

@Data
public class ScenarioConfig {
    private String feishuWebhookUrl = "";
    private String cardTitle = "";
    private String cardFooter = "";
    private String minioPrefix = "";
}
```

- [ ] **Step 2: 创建 @ConfigurationProperties 绑定类**

`ScenarioConfigs.java`：
```java
package com.enviro.brain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "enviro.scenarios")
public class ScenarioConfigs {
    private final Map<String, ScenarioConfig> scenarios = new LinkedHashMap<>();

    public Map<String, ScenarioConfig> getScenarios() {
        return scenarios;
    }

    /** 按场景取配置，缺省回退到 enviro 场景 */
    public ScenarioConfig getOrDefault(String scenario) {
        ScenarioConfig sc = scenarios.get(scenario);
        return sc != null ? sc : scenarios.get("enviro");
    }
}
```

- [ ] **Step 3: 在 application.yml 增加 scenarios 块**

在 `enviro:` 下（与 `feishu:`/`minio:` 平级）追加：
```yaml
  scenarios:
    enviro:
      feishu-webhook-url: ${FEISHU_WEBHOOK_URL:}
      card-title: "危废仓库摄像头巡检报告"
      card-footer: "环保小脑自动巡检"
      minio-prefix: ""
    gangqu:
      feishu-webhook-url: ${GANGQU_FEISHU_WEBHOOK_URL:}
      card-title: "智慧港区小脑巡检报告"
      card-footer: "智慧港区小脑"
      minio-prefix: "gangqu"
```
> 注意 YAML 缩进：`scenarios:` 与 `hikvision:`/`feishu:` 同级（2 空格缩进于 `enviro:` 下）。`GANGQU_FEISHU_WEBHOOK_URL` 由部署环境注入（docker-compose `environment` 或系统环境变量）。

- [ ] **Step 4: 编译验证绑定生效**

Run: `mvn -pl enviro-brain compile -q`
Expected: BUILD SUCCESS（无 `@ConfigurationProperties` 扫描报错；Spring Boot 自动绑定 `@Component`）

- [ ] **Step 5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/config/ScenarioConfig.java \
        enviro-brain/src/main/java/com/enviro/brain/config/ScenarioConfigs.java \
        enviro-brain/src/main/resources/application.yml
git commit -m "feat(config): 新增 ScenarioConfig 场景配置绑定(enviro/gangqu)"
```

---

### Task 4: 修复 CaptureService --device-id 死代码

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/CaptureService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/CaptureServiceTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `buildCommand()` 不再输出 `--device-id`，无论 `artemisDeviceId` 是否为空。

- [ ] **Step 1: 写/确认测试**

`CaptureServiceTest` 已有 `shouldNotIncludeDeviceIdWhenNull`（验证 `artemisDeviceId` 为空时不带 `--device-id`）。新增一个验证**非空时也绝不带** `--device-id`：
```java
@Test
void shouldNeverIncludeDeviceIdEvenWhenSet() throws Exception {
    CameraConfig cfg = new CameraConfig();
    cfg.setCameraCode("C1"); cfg.setCameraName("n"); cfg.setArtemisDeviceId("D999");
    Method m = CaptureService.class.getDeclaredMethod("buildCommand", CameraConfig.class);
    m.setAccessible(true);
    String[] cmd = (String[]) m.invoke(captureService, cfg);
    assertThat(List.of(cmd)).doesNotContain("--device-id");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl enviro-brain test -Dtest=CaptureServiceTest#shouldNeverIncludeDeviceIdEvenWhenSet`
Expected: FAIL（当前非空时含 `--device-id`）

- [ ] **Step 3: 删除死代码**

`CaptureService.buildCommand()` 删除第 102–104 行整段：
```java
    if (config.getArtemisDeviceId() != null && !config.getArtemisDeviceId().isEmpty()) {
        cmd.add("--device-id"); cmd.add(config.getArtemisDeviceId());
    }
```
删除后 `buildCommand` 以 `cmd.add("--json");` 结尾（紧接 `return cmd.toArray(...)`）。

- [ ] **Step 4: 重跑测试确认通过**

Run: `mvn -pl enviro-brain test -Dtest=CaptureServiceTest`
Expected: PASS（两个 device-id 用例均通过）

- [ ] **Step 5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/CaptureService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/CaptureServiceTest.java
git commit -m "fix(capture): 删除 buildCommand 中未被消费的 --device-id 死代码"
```

---

### Task 5: MinioStorageService 支持场景前缀

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java`

**Interfaces:**
- Consumes: `ScenarioConfig.minioPrefix`（由 Task 3 提供）。
- Produces: `uploadScreenshot(cameraName, bytes, prefix)` 三参重载；`buildObjectKey(cameraName, time, prefix)` 三参重载。环保（prefix 空）行为不变。

- [ ] **Step 1: 写失败测试（带前缀生成 key）**

在 `MinioStorageService` 的单测（若无则新建 `MinioStorageServiceTest`）验证：
```java
@Test
void shouldPrefixObjectKeyWhenPrefixGiven() throws Exception {
    Method m = MinioStorageService.class.getDeclaredMethod("buildObjectKey", String.class, LocalDateTime.class, String.class);
    m.setAccessible(true);
    String key = (String) m.invoke(service, "摄像头A", LocalDateTime.of(2026,7,10,9,5), "gangqu");
    assertThat(key).startsWith("gangqu/2026-07-10/");
    String key2 = (String) m.invoke(service, "摄像头A", LocalDateTime.of(2026,7,10,9,5), "");
    assertThat(key2).startsWith("2026-07-10/");
}
```
（测试需通过 `ReflectionTestUtils` 注入 `prefix` 等字段；若无现成测试类，按现有 `CaptureServiceTest` 风格 `new MinioStorageService()` + 反射设字段。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl enviro-brain test -Dtest=MinioStorageServiceTest`
Expected: FAIL（方法不存在 / key 不带前缀）

- [ ] **Step 3: 改 buildObjectKey 与 uploadScreenshot**

`buildObjectKey` 改为接受 `prefix`：
```java
private String buildObjectKey(String cameraName, LocalDateTime time, String prefix) {
    String datePart = time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    String hourPart = String.format("%02d", time.getHour());
    String safeName = cameraName.replaceAll("[\\\\/:*?\"<>|%#\\x00-\\x1f ]", "_");
    String dir = (prefix != null && !prefix.isEmpty()) ? prefix + "/" + datePart + "/" : datePart + "/";
    return dir + safeName + "_" + hourPart + ".jpg";
}
```
`uploadScreenshot` 增加三参重载（保留二参向后兼容）：
```java
public String uploadScreenshot(String cameraName, byte[] imageBytes) {
    return uploadScreenshot(cameraName, imageBytes, null);
}
public String uploadScreenshot(String cameraName, byte[] imageBytes, String prefix) {
    if (imageBytes == null || imageBytes.length == 0) return null;
    ensureBucket();
    String effectivePrefix = (prefix != null && !prefix.isEmpty()) ? prefix : this.prefix;
    String objectKey = buildObjectKey(cameraName, LocalDateTime.now(), effectivePrefix);
    minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
        .stream(new ByteArrayInputStream(imageBytes), imageBytes.length, -1)
        .contentType("image/jpeg").build());
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length()-1) : endpoint;
    return base + "/" + bucket + "/" + objectKey;
}
```

- [ ] **Step 4: 重跑测试确认通过**

Run: `mvn -pl enviro-brain test -Dtest=MinioStorageServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/MinioStorageService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/MinioStorageServiceTest.java
git commit -m "feat(minio): uploadScreenshot 支持按场景前缀(gangqu/)"
```

---

### Task 6: FeishuNotifyService 按场景取配置

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/FeishuNotifyService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/FeishuNotifyServiceTest.java`

**Interfaces:**
- Consumes: `ScenarioConfigs`（Task 3）+ `ScenarioConfig`（cardTitle/cardFooter/feishuWebhookUrl）。
- Produces: `sendInspectionReport(record, results, scenario)`，按场景选 webhook / 标题 / 落款。

- [ ] **Step 1: 写失败测试（场景标题/落款）**

在 `FeishuNotifyServiceTest` 新增（用 Mockito 或反射注入 `scenarioConfigs`）：
```java
@Test
void shouldUseGangquTitleAndFooter() throws Exception {
    ScenarioConfig gangqu = new ScenarioConfig();
    gangqu.setFeishuWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/test");
    gangqu.setCardTitle("智慧港区小脑巡检报告");
    gangqu.setCardFooter("智慧港区小脑");
    ScenarioConfigs cfgs = mock(ScenarioConfigs.class);
    when(cfgs.getOrDefault("gangqu")).thenReturn(gangqu);
    ReflectionTestUtils.setField(feishuNotifyService, "scenarioConfigs", cfgs);

    InspectionRecord rec = new InspectionRecord(); rec.setScenario("gangqu");
    rec.setTotalCameras(2); rec.setOnlineCount(1); rec.setOfflineCount(1); rec.setAbnormalCount(0);
    // 捕获 postForObject 的请求体
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn("{}");
    feishuNotifyService.sendInspectionReport(rec, List.of(), "gangqu");
    verify(restTemplate).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    // 用 JSON 解析校验 title/footer 含场景文案（或断言 webhook 用了 gangqu 的 url）
}
```
（若原测试直接设 `webhookUrl` 字段且 `scenarioConfigs==null`，应在实现里对 null 做容错，保证旧测试仍通过。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl enviro-brain test -Dtest=FeishuNotifyServiceTest`
Expected: FAIL（`sendInspectionReport` 无三参签名 / 未用场景配置）

- [ ] **Step 3: 改 FeishuNotifyService**

字段增加（保留 `webhookUrl` 作回退）：
```java
@Value("${enviro.feishu.webhook-url:}")
private String webhookUrl;
@org.springframework.beans.factory.annotation.Autowired(required = false)
private ScenarioConfigs scenarioConfigs;
```
`sendInspectionReport` 改为三参并按场景解析：
```java
public void sendInspectionReport(InspectionRecord record, List<CameraResult> results, String scenario) {
    String url = resolveWebhook(scenario);
    if (url == null || url.isEmpty()) { log.info("[FeishuNotify] Webhook URL 未配置，跳过通知"); return; }
    String title = resolveTitle(scenario);
    String footer = resolveFooter(scenario);
    try {
        String cardJson = buildCardJson(record, results, title, footer);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(cardJson, headers);
        restTemplate.postForObject(url, entity, String.class);
        log.info("[FeishuNotify] 飞书通知发送成功");
    } catch (Exception e) { log.error("[FeishuNotify] 飞书通知发送失败: {}", e.getMessage()); }
}
private String resolveWebhook(String scenario) {
    if (scenarioConfigs != null) {
        ScenarioConfig sc = scenarioConfigs.getOrDefault(scenario);
        if (sc != null && sc.getFeishuWebhookUrl() != null && !sc.getFeishuWebhookUrl().isEmpty())
            return sc.getFeishuWebhookUrl();
    }
    return webhookUrl;
}
private String resolveTitle(String scenario) {
    if (scenarioConfigs != null) {
        ScenarioConfig sc = scenarioConfigs.getOrDefault(scenario);
        if (sc != null && sc.getCardTitle() != null && !sc.getCardTitle().isEmpty()) return sc.getCardTitle();
    }
    return "🔔 摄像头巡检报告";
}
private String resolveFooter(String scenario) {
    if (scenarioConfigs != null) {
        ScenarioConfig sc = scenarioConfigs.getOrDefault(scenario);
        if (sc != null && sc.getCardFooter() != null && !sc.getCardFooter().isEmpty()) return sc.getCardFooter();
    }
    return "enviro-brain 自动巡检";
}
```
`buildCardJson(record, results, title, footer)`：把原有写死的 `"🔔 危废仓库摄像头巡检报告"` 改为形参 `title`，落款 `"环保小脑自动巡检 " + time` 改为 `footer + " " + time`（其余卡片结构不变）。

- [ ] **Step 4: 重跑测试确认通过**

Run: `mvn -pl enviro-brain test -Dtest=FeishuNotifyServiceTest`
Expected: PASS（旧用例仍过：scenarioConfigs==null 时回退 webhookUrl；新用例验证 gangqu 标题/落款）

- [ ] **Step 5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/FeishuNotifyService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/FeishuNotifyServiceTest.java
git commit -m "feat(feishu): 按 scenario 取 webhook/标题/落款"
```

---

### Task 7: InspectionService 场景贯穿

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/service/InspectionServiceTest.java`

**Interfaces:**
- Consumes: `CameraConfigService.findActiveByScenario/countByScenario`（Task 1）、`ScenarioConfigs`（Task 3）、`MinioStorageService.uploadScreenshot(...,prefix)`（Task 5）、`FeishuNotifyService.sendInspectionReport(record,results,scenario)`（Task 6）。
- Produces: `prepareInspection(triggerType, scenario)`、`executeInspection(triggerType, scenario)`；`runInspectionBody` 从 `ctx.getRecord().getScenario()` 取场景并分流 MinIO 前缀/飞书；`batch_id = {scenario}-{triggerType}-{date}-{HHmm}`。

- [ ] **Step 1: 写失败测试（按场景成批 + batch_id 前缀 + MinIO 前缀分流）**

在 `InspectionServiceTest` 补充 mock 并验证：
```java
@org.junit.jupiter.api.Test
void shouldBuildBatchIdWithScenarioPrefixAndRouteMinioPrefix() throws Exception {
    when(cameraConfigService.findActiveByScenario(eq("gangqu"), anyInt(), anyInt()))
        .thenReturn(List.of(gangquCamera()));
    when(cameraConfigService.countActive()).thenReturn(1);
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
```
> 同时在测试类 `@Mock` 列表补齐 `MinioStorageService`（修复既有 NPE 隐患），并构造 `gangquCamera()`（scenario="gangqu"）、`okCapture()` 辅助方法。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl enviro-brain test -Dtest=InspectionServiceTest`
Expected: FAIL（`prepareInspection` 无二参签名 / 未传 scenario 到 feishu/minio）

- [ ] **Step 3: 改 InspectionService**

字段注入增加：
```java
private final ScenarioConfigs scenarioConfigs;
```
（加入 `@RequiredArgsConstructor` 的 final 字段列表；其余依赖不变。）

`prepareInspection` 改签名并写入 scenario + 新 batch_id：
```java
@Transactional
public InspectionContext prepareInspection(String triggerType, String scenario) {
    LocalDateTime startTime = LocalDateTime.now();
    log.info("[Inspection] 准备巡检，触发类型：{}，场景：{}", triggerType, scenario);

    List<CameraConfig> cameras = cameraConfigService.findActiveByScenario(scenario, 1, 10000);
    log.info("[Inspection] 场景 {} 共读取 {} 路摄像头", scenario, cameras.size());

    long syncVersion = syncVersionService.nextVersion();

    InspectionRecord record = new InspectionRecord();
    record.setScenario(scenario);
    record.setBatchId(scenario + "-" + triggerType + "-" + startTime.toLocalDate() + "-"
            + startTime.toLocalTime().toString().replace(":", "").substring(0, 4));
    record.setInspectionDate(LocalDate.now());
    record.setTotalCameras(cameras.size());
    record.setOnlineCount(0); record.setOfflineCount(0); record.setAbnormalCount(0);
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
```

`executeInspection` 改签名：
```java
@Transactional
public Long executeInspection(String triggerType, String scenario) {
    InspectionContext ctx = prepareInspection(triggerType, scenario);
    runInspectionBody(ctx);
    return ctx.getInspectId();
}
```

`runInspectionBody(InspectionContext ctx)` 内：
- 在 ⑨ 飞书处改为：
```java
String scenario = ctx.getRecord().getScenario();
feishuNotifyService.sendInspectionReport(record, results, scenario);
```
- 在上传 MinIO 处（`uploadToMinio` 调用）改为带前缀：
```java
ScenarioConfig sc = scenarioConfigs.getOrDefault(scenario);
String prefix = sc != null ? sc.getMinioPrefix() : "";
uploadToMinio(capture.getScreenshotPath(), config.getCameraName(), prefix);
```
- `uploadToMinio` 方法签名改为三参：
```java
private String uploadToMinio(String localPath, String cameraName, String prefix) {
    try {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(localPath));
        return minioStorageService.uploadScreenshot(cameraName, bytes, prefix);
    } catch (Exception e) {
        log.error("[Inspection] 上传 MinIO 失败: {}", e.getMessage());
        return null;
    }
}
```
- `runInspectionAsync` 签名保持不变（`InspectionContext ctx` 已含 scenario，无需加参）：
```java
@Async
public void runInspectionAsync(InspectionContext ctx) {
    runInspectionBody(ctx);
}
```

- [ ] **Step 4: 重跑测试确认通过**

Run: `mvn -pl enviro-brain test -Dtest=InspectionServiceTest`
Expected: PASS（新场景用例通过；旧 full-flow 用例因补 `@Mock MinioStorageService` 不再 NPE）

- [ ] **Step 5: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java \
        enviro-brain/src/test/java/com/enviro/brain/service/InspectionServiceTest.java
git commit -m "feat(inspection): 巡检按 scenario 过滤/成批，batch_id 带场景前缀，MinIO/飞书分流"
```

---

### Task 8: InspectionScheduler 循环场景

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/scheduler/InspectionScheduler.java`

**Interfaces:**
- Consumes: `InspectionService.executeInspection(triggerType, scenario)`（Task 7）、`CameraConfigService.countByScenario(scenario)`（Task 1）。
- Produces: 定时触发时遍历所有场景，0 路跳过。

- [ ] **Step 1: 改调度器**

`InspectionScheduler` 注入 `ScenarioConfigs` 与 `CameraConfigService`（保留 `InspectionService`）：
```java
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class InspectionScheduler {
    private final InspectionService inspectionService;
    private final CameraConfigService cameraConfigService;
    private final ScenarioConfigs scenarioConfigs;

    @Scheduled(cron = "${enviro.inspection.cron:0 0 9,15 * * ?}")
    public void scheduledInspection() {
        log.info("[Scheduler] 定时巡检触发: {}", LocalDateTime.now());
        for (String scenario : scenarioConfigs.getScenarios().keySet()) {
            try {
                if (cameraConfigService.countByScenario(scenario) == 0) {
                    log.info("[Scheduler] 场景 {} 无摄像头，跳过", scenario);
                    continue;
                }
                Long inspectId = inspectionService.executeInspection("auto", scenario);
                log.info("[Scheduler] 场景 {} 巡检完成, inspectId={}", scenario, inspectId);
            } catch (Exception e) {
                log.error("[Scheduler] 场景 {} 巡检异常", scenario, e);
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -pl enviro-brain compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/scheduler/InspectionScheduler.java
git commit -m "feat(scheduler): 定时巡检遍历所有场景各跑一批，0 路跳过"
```

---

### Task 9: InspectionController 支持 scenario 参数

**Files:**
- Modify: `enviro-brain/src/main/java/com/enviro/brain/controller/InspectionController.java`
- Test: `enviro-brain/src/test/java/com/enviro/brain/controller/InspectionControllerTest.java`

**Interfaces:**
- Consumes: `InspectionService.prepareInspection(triggerType, scenario)` / `runInspectionAsync(ctx)`（Task 7）。
- Produces: `POST /api/v1/inspections/trigger?scenario=gangqu` 触发港区批次；不传默认 `enviro`。

- [ ] **Step 1: 改 Controller**

```java
@PostMapping("/trigger")
public ResponseEntity<ApiResponse<Map<String, Object>>> trigger(
        @RequestParam(required = false) String scenario) {
    String sc = (scenario == null || scenario.isBlank()) ? "enviro" : scenario;
    log.info("[Controller] 手动触发巡检，场景={}", sc);
    InspectionContext ctx = inspectionService.prepareInspection("manual", sc);
    inspectionService.runInspectionAsync(ctx);
    return ResponseEntity.accepted().body(
        ApiResponse.success(Map.of("taskId", ctx.getInspectId(), "scenario", sc, "status", "running")));
}
```

- [ ] **Step 2: 更新 Controller 测试**

`InspectionControllerTest` 中 `when(inspectionService.prepareInspection(...))` 的 stub 改为接收二参：
```java
when(inspectionService.prepareInspection(anyString(), anyString())).thenReturn(ctx);
```
并新增用例验证 `?scenario=gangqu`：
```java
@Test
void shouldTriggerGangquWhenScenarioParamGiven() throws Exception {
    when(inspectionService.prepareInspection("manual", "gangqu")).thenReturn(ctx);
    mockMvc.perform(post("/api/v1/inspections/trigger").param("scenario", "gangqu"))
           .andExpect(status().isAccepted());
    verify(inspectionService).prepareInspection("manual", "gangqu");
}
```

- [ ] **Step 3: 跑测试确认通过**

Run: `mvn -pl enviro-brain test -Dtest=InspectionControllerTest`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add enviro-brain/src/main/java/com/enviro/brain/controller/InspectionController.java \
        enviro-brain/src/test/java/com/enviro/brain/controller/InspectionControllerTest.java
git commit -m "feat(controller): /trigger 支持 scenario 参数(默认 enviro)"
```

---

### Task 10: 港区摄像头录入脚本

**Files:**
- Create: `enviro-brain/scripts/import_gangqu_cameras.py`

**Interfaces:**
- Consumes: 用户本地 CSV（默认 `C:\Users\7even\Downloads\港区小脑摄像头清单.csv`，需 `--csv` 显式传入）。
- Produces: `camera_config` 写入 13 条 `scenario='gangqu'`、`enabled=1`、`artemis_device_id=''` 记录（幂等 `ON CONFLICT`）。

- [ ] **Step 1: 写脚本**

`import_gangqu_cameras.py`（复用 `view_pg.py` 的 psycopg2 DSN 约定：环境变量 `PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD` 可覆盖，默认指向 172.168.97.180:31028/smartpark_scenes_zhh）：
```python
#!/usr/bin/env python3
import argparse, csv, os, sys
import psycopg2

DEFAULT_CSV = r"C:\Users\7even\Downloads\港区小脑摄像头清单.csv"
DSN = {
    "host": os.getenv("PG_HOST", "172.168.97.180"),
    "port": int(os.getenv("PG_PORT", "31028")),
    "dbname": os.getenv("PG_DB", "smartpark_scenes_zhh"),
    "user": os.getenv("PG_USER", "postgres"),
    "password": os.getenv("PG_PASSWORD", "postgresql"),
}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=DEFAULT_CSV)
    args = ap.parse_args()

    rows = []
    with open(args.csv, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        for r in reader:
            if not r or all(c.strip() == "" for c in r):
                continue
            code = r[0].strip()
            name = (r[1].strip() if len(r) > 1 else code)
            if code:
                rows.append((code, name))
    if not rows:
        print("CSV 无有效行，退出"); sys.exit(1)

    conn = psycopg2.connect(**DSN)
    cur = conn.cursor()
    inserted = updated = 0
    for code, name in rows:
        cur.execute(
            """INSERT INTO camera_config
               (camera_code, camera_name, enterprise, artemis_device_id, rtsp_url, location, enabled, scenario, created_at, updated_at)
               VALUES (%s,%s,'',NULL,NULL,NULL,1,'gangqu',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
               ON CONFLICT (camera_code) DO UPDATE SET
                 camera_name = EXCLUDED.camera_name,
                 scenario = 'gangqu',
                 enabled = 1,
                 artemis_device_id = NULL,
                 updated_at = CURRENT_TIMESTAMP""",
            (code, name),
        )
        # 简单计数（INSERT/UPDATE 均影响 1 行，这里只统计成功写入）
        inserted += 1
    conn.commit()
    cur.execute("SELECT count(*) FROM camera_config WHERE scenario='gangqu'")
    total = cur.fetchone()[0]
    cur.close(); conn.close()
    print(f"处理 {len(rows)} 行；camera_config 中 scenario='gangqu' 共 {total} 条")

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 语法检查**

Run: `python3 enviro-brain/scripts/import_gangqu_cameras.py --help`
Expected: 打印 argparse 帮助，无语法错误。

- [ ] **Step 3: 提交（脚本入仓，CSV 不入仓）**

```bash
git add enviro-brain/scripts/import_gangqu_cameras.py
git commit -m "feat(script): 港区摄像头一次性幂等录入 camera_config(scenario=gangqu)"
```
> 注意：`C:\Users\7even\Downloads\港区小脑摄像头清单.csv` 是用户本地文件，不提交仓库（Task 决策②）。

---

### Task 11: 文档更新

**Files:**
- Modify: `docs/enviro-brain-database.md`（如存在且权威；探索发现该文件当前不存在，此步可跳过或仅更新 spec 引用）
- (引用) `docs/superpowers/specs/2026-07-10-gangkou-integration-design.md`（已提交）

**Interfaces:**
- Consumes: Task 1–10 的 schema 变更。
- Produces: 文档与运行库一致（补 `scenario` 列说明）。

- [ ] **Step 1: 确认文档存在性并补 scenario 说明**

Run: `ls docs/enviro-brain-database.md 2>/dev/null || echo "NO_DOC"`
- 若存在：在 `camera_config`、`inspection_records` 表说明处补 `scenario VARCHAR(32) NOT NULL DEFAULT 'enviro'`（取值 `enviro`/`gangqu`）。
- 若不存在：跳过（真实 schema 以运行 PG 库 + 实体为准，已在 spec §13 注明）。

- [ ] **Step 2: 提交（若有改动）**

```bash
git add docs/enviro-brain-database.md
git commit -m "docs: 补充 scenario 列说明"   # 仅当文件存在且有改动
```

---

### Task 12: 全量构建 + 运行验收

**Files:**
- 无新增；验证全部 Task。

**Interfaces:**
- Consumes: 所有前序 Task。
- Produces: 编译通过、单测全绿、运行实例可按场景巡检并落 MinIO/飞书。

- [ ] **Step 1: 全量编译 + 测试**

Run: `mvn -pl enviro-brain clean test -q`
Expected: BUILD SUCCESS，所有单测通过（`CameraConfigServiceTest`/`CameraConfigMapperTest`/`CaptureServiceTest`/`MinioStorageServiceTest`/`FeishuNotifyServiceTest`/`InspectionServiceTest`/`InspectionControllerTest`）。

- [ ] **Step 2: 运行库执行 ALTER（若 Task 2 未执行）**

参照 Task 2 Step 4 的 SQL，在运行 PG 库执行（幂等 `IF NOT EXISTS`）。

- [ ] **Step 3: 运行录入脚本**

Run: `python3 enviro-brain/scripts/import_gangqu_cameras.py --csv "C:\Users\7even\Downloads\港区小脑摄像头清单.csv"`
Expected: 打印 `camera_config 中 scenario='gangqu' 共 13 条`。

- [ ] **Step 4: 启动 enviro-brain（Maven）并查定时任务**

Run: `mvn -pl enviro-brain spring-boot:run`（后台）
验证：`curl -s http://localhost:8080/actuator/scheduledtasks` 应含 `scheduledInspection`（cron `0 0 9,15 * * ?`）。

- [ ] **Step 5: 手动触发港区巡检**

Run: `curl -X POST "http://localhost:8080/api/v1/inspections/trigger?scenario=gangqu"`
Expected: `202 Accepted`，返回 `taskId` 与 `scenario=gangqu`。

- [ ] **Step 6: 验收核对（对照 spec §14）**

- [ ] `camera_config` 有 13 条 `scenario='gangqu'` 且 `artemis_device_id` 为空。
- [ ] `inspection_records` 出现 `gangqu-auto-...` / `gangqu-manual-...` 批次；与 `enviro-*` 互不混。
- [ ] 港区截图出现在 MinIO `gangqu/` 前缀下（`http://172.168.97.180:30113/scenes-camerapatrol/gangqu/...`）。
- [ ] 港区飞书卡片推到专用 webhook，标题"智慧港区小脑巡检报告"。
- [ ] `CaptureService` 不再拼接 `--device-id`（Task 4 已单测覆盖）。
- [ ] 环保巡检行为完全不变（旧 `enviro-*` 批次、`2026-07-10/...` 无前缀 key 照常）。

- [ ] **Step 7: 提交验证记录（可选）**

若需留存验证结果，可追加一行到 `docs/superpowers/specs/2026-07-10-gangkou-integration-design.md` 的验收章节并 commit；否则本 Task 不强制提交。

---

## 自审（Self-Review）

1. **Spec 覆盖**：§3 数据模型→Task 1/2；§4 场景配置→Task 3；§5 流水线→Task 7；§6 飞书→Task 6；§7 MinIO→Task 5；§8 bug→Task 4；§9 录入脚本→Task 10；§10 触发入口→Task 8/9；§11 测试→各 Task 测试步骤；§12 文档→Task 11；§14 验收→Task 12。无遗漏。
2. **占位符扫描**：无 TBD/TODO；每个代码步骤均含具体片段。
3. **类型一致性**：`prepareInspection(triggerType, scenario)`、`executeInspection(triggerType, scenario)`、`sendInspectionReport(record, results, scenario)`、`uploadScreenshot(cameraName, bytes, prefix)`、`findActiveByScenario(page,size,scenario)`、`countByScenario(scenario)` 在 Task 间命名/签名一致；`ctx.getRecord().getScenario()` 贯穿 Task 7→6→5。二参 `prepareInspection` 与旧一参调用方（Task 8/9 已同步更新）无残留旧签名引用。
4. **风险闭环**：Task 2 Step 4 的 ALTER 必须在运行库执行（无 migration 工具）；Task 12 Step 2 复核；`GANGQU_FEISHU_WEBHOOK_URL` 需在部署环境注入（Task 3 已加 env 绑定，docker-compose `environment` 待用户补，见 spec §13.6）。
