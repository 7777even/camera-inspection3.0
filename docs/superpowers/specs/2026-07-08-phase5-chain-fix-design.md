# Phase 5 链路问题修复设计

> 状态：设计稿（待用户审阅）
> 日期：2026-07-08
> 上游：Phase 5 完整链路测试报告（`docs/Phase5完整链路测试报告.md`）
> 目标：修复测试发现的 2 个 P1 + 3 个 P2 问题，使完整链路可生产上线

---

## 1. 问题清单

| 编号 | 级别 | 问题 | 影响 |
|------|------|------|------|
| P1-1 | P1 | trigger 端点同步阻塞 | HTTP 响应需等全量巡检完成（~4min），MCP SSE 超时、Tomcat 线程池耗尽 |
| P1-2 | P1 | QueqiaoNotifyService 回调缺 X-API-Key | 巡检完成后回调鹊桥被 401 拦截，自动同步链路不通 |
| P2-1 | P2 | smoke-test.sh 参数名错误 | 冒烟脚本无法正确验证查询和下载功能 |
| P2-2 | P2 | smoke-test.sh notify 端点错误 | 冒烟脚本同步触发失败 |
| P2-3 | P2 | callback-url 默认为空 | 自动同步仅靠 30min 定时任务兜底 |

---

## 2. 非目标（明确排除）

- 不重构 InspectionService 的核心截图逻辑
- 不改变 MCP 工具的参数名（已有调用方依赖）
- 不改变数据库表结构
- 不新增 MCP 工具

---

## 3. P1-1 修复：trigger 端点异步化

### 3.1 设计思路

将 `executeInspection` 拆为两段：

```
prepareInspection(triggerType)        → @Transactional, 同步, 返回 InspectionContext
runInspectionBody(ctx)                → 无事务注解, 可同步或异步调用
runInspectionAsync(ctx)               → @Async, 异步, 内部调 runInspectionBody
executeInspection(triggerType)        → 同步组合（调度器用）, 调 prepare + body
```

### 3.2 新增 DTO: InspectionContext

```java
package com.enviro.brain.dto;

@Data
public class InspectionContext {
    private Long inspectId;
    private long syncVersion;
    private List<CameraConfig> cameras;
    private InspectionRecord record;
}
```

### 3.3 InspectionService 改造

```java
// 新增：Phase 1 — 创建 RUNNING 记录（同步, @Transactional）
@Transactional
public InspectionContext prepareInspection(String triggerType) {
    LocalDateTime startTime = LocalDateTime.now();
    List<CameraConfig> cameras = cameraConfigService.findActive(1, 10000);
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

// 新增：Phase 2 — 执行巡检主体（无事务注解, 各 DB 操作自动提交）
public void runInspectionBody(InspectionContext ctx) {
    // 原 executeInspection 的 ④⑤⑥⑦⑧⑨ 步骤
    // ④ 并发截图
    // ⑤ 批量写 camera_results
    // ⑥ 汇总统计 + 更新记录为 COMPLETED
    // ⑦ 生成台账
    // ⑧ 飞书通知
    // ⑨ 鹊桥回调
}

// 新增：异步入口（@Async, 控制器用）
@Async
public void runInspectionAsync(InspectionContext ctx) {
    runInspectionBody(ctx);
}

// 保留：同步入口（调度器用, 行为不变）
@Transactional
public Long executeInspection(String triggerType) {
    InspectionContext ctx = prepareInspection(triggerType);
    runInspectionBody(ctx);
    return ctx.getInspectId();
}
```

**注意**：`prepareInspection` 自调用时 `@Transactional` 不走代理。但因为 `executeInspection` 本身标了 `@Transactional`，其事务会覆盖子调用，所以调度器路径没有问题。控制器路径中 `prepareInspection` 由控制器从外部调用，走代理，事务正常。

### 3.4 InspectionController 改造

```java
@PostMapping("/trigger")
public ResponseEntity<ApiResponse<Map<String, Object>>> trigger() {
    log.info("[Controller] 手动触发巡检");
    InspectionContext ctx = inspectionService.prepareInspection("manual");
    inspectionService.runInspectionAsync(ctx);
    return ResponseEntity.accepted().body(
            ApiResponse.success(Map.of("taskId", ctx.getInspectId(), "status", "running"))
    );
}
```

### 3.5 启用 @EnableAsync

在 `EnviroBrainApplication` 上添加 `@EnableAsync`：

```java
@SpringBootApplication
@EnableAsync
public class EnviroBrainApplication { ... }
```

默认使用 `SimpleAsyncTaskExecutor`（每次创建新线程）。巡检频率低（每小时最多一次手动+一次定时），无需配置线程池。如果未来需要限制并发，可添加 `AsyncConfigurer` 配置。

### 3.6 调度器不变

`InspectionScheduler.hourlyInspection()` 继续调用同步的 `executeInspection("auto")`，行为不变。

---

## 4. P1-2 + P2-3 修复：回调认证 + callback-url 配置

### 4.1 QueqiaoNotifyService 改造

```java
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
            headers.set("X-API-Key", notifyApiKey);  // ← 新增
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

### 4.2 application.yml 配置

```yaml
enviro:
  queqiao:
    callback-url: ${QUEQIAO_CALLBACK_URL:http://localhost:8081/api/notify/new-data}
    notify-api-key: ${QUEQIAO_NOTIFY_API_KEY:queqiao-notify-key-2026}
```

- dev 环境：默认值直接可用（localhost:8081）
- prod 环境：通过 `QUEQIAO_CALLBACK_URL=http://queqiao:8081/api/notify/new-data` 覆盖

### 4.3 .env.template 补充

```bash
# 鹊桥回调配置
QUEQIAO_CALLBACK_URL=http://localhost:8081/api/notify/new-data
QUEQIAO_NOTIFY_API_KEY=queqiao-notify-key-2026
```

---

## 5. P2-1 + P2-2 修复：smoke-test.sh 脚本修正

### 5.1 参数名修正

| 工具 | 错误参数名 | 正确参数名 |
|------|-----------|-----------|
| get_inspection_summary | start_date, end_date | start, end |
| download_ledger_docx | inspect_id | inspectId |

### 5.2 notify 端点修正

```bash
# 修正前
curl -sf "http://localhost:8081/api/notify?syncVersion=$LATEST_SYNC" > /dev/null 2>&1 || true

# 修正后
curl -sf -X POST "http://localhost:8081/api/notify/new-data" \
  -H "X-API-Key: queqiao-notify-key-2026" \
  -H "Content-Type: application/json" \
  -d "{\"syncVersion\": $LATEST_SYNC, \"type\": \"inspection_completed\"}" > /dev/null 2>&1 || true
```

### 5.3 trigger_inspection 超时处理

由于 trigger 端点改为异步，`trigger_inspection` 现在会立即返回 taskId。但 smoke-test.sh 中通过 MCP SSE 调用 trigger_inspection 的 Python 代码仍需要适当超时保护。

---

## 6. 涉及文件清单

| # | 文件 | 修改类型 | 说明 |
|---|------|----------|------|
| 1 | `enviro-brain/src/main/java/com/enviro/brain/dto/InspectionContext.java` | 新增 | 巡检上下文 DTO |
| 2 | `enviro-brain/src/main/java/com/enviro/brain/service/InspectionService.java` | 修改 | 拆分 prepare/body/async |
| 3 | `enviro-brain/src/main/java/com/enviro/brain/controller/InspectionController.java` | 修改 | 改用 prepare + async |
| 4 | `enviro-brain/src/main/java/com/enviro/brain/EnviroBrainApplication.java` | 修改 | 添加 @EnableAsync |
| 5 | `enviro-brain/src/main/java/com/enviro/brain/service/QueqiaoNotifyService.java` | 修改 | 添加 X-API-Key 头 |
| 6 | `enviro-brain/src/main/resources/application.yml` | 修改 | callback-url + notify-api-key 默认值 |
| 7 | `.env.template` | 修改 | 补充回调配置项 |
| 8 | `scripts/smoke-test.sh` | 修改 | 参数名 + notify 端点修正 |

---

## 7. 验证方案

修复完成后重新执行完整链路测试：

1. **trigger 异步验证**：curl POST trigger → 1s 内返回 taskId → 轮询 DB 确认 status 从 RUNNING → COMPLETED
2. **回调验证**：巡检完成后检查 queqiao synced_inspection_records 是否自动新增（无需手动触发 notify）
3. **smoke-test.sh 验证**：执行修正后的脚本，确认 5 项检查全部 PASS
4. **调度器回归**：等待整点定时巡检触发，确认行为不变
