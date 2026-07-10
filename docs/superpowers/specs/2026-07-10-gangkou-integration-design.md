# 港区小脑接入 enviro-brain 设计文档

- 日期：2026-07-10
- 作者：WorkBuddy（brainstorming → 本文档 → writing-plans）
- 状态：待用户评审

## 1. 背景与目标

当前 enviro-brain 是**纯单场景（环保小脑 / 危废仓库）架构**：`camera_config`、`camera_results`、`inspection_records` 三张业务表均无场景字段，海康凭证全局一份，飞书卡片标题写死"危废仓库"，巡检读 `camera_config` 全表不区分。

本次目标：**把"港区小脑"作为第二个场景接入 enviro-brain 自有巡检流水线**，使港区摄像头由 enviro-brain 抓拍、上传 MinIO、发飞书，而非依赖外部巡检 skill（skill 只落本地+发飞书，不写 MinIO）。

约束与已确认决策：

| 项 | 决策 |
|---|---|
| 海康取流平台 | 与环保**同一平台**（172.168.97.251:443，共用 appKey/appSecret），仅 cameraIndexCode 不同 → 取流逻辑零改动 |
| 接入方式 | **全场景化**：给系统补 scenario 维度，各场景独立批次 / 飞书 / MinIO 前缀 |
| 摄像头录入 | **一次性 CSV 导入**：`scripts/import_gangqu_cameras.py` 读用户本地 CSV，写入 `camera_config`，`scenario='gangqu'`；**不提交 CSV 副本进仓库** |
| 飞书 | 港区用专用 webhook（`https://open.feishu.cn/open-apis/bot/v2/hook/56caf1a4-29e3-4552-9a8c-3521be2fdc88`），卡片标题"智慧港区小脑巡检报告"、落款"智慧港区小脑" |
| 触发 | 与环保**同 cron（9:00/15:00）各自独立批次**，batch_id 带场景前缀 |
| MinIO | 港区用 `gangqu/` 前缀，与环保同 bucket 分目录；清理策略（保留 7 天 / 每日 02:00）复用 |
| 附带修复 | **修 `artemisDeviceId` / `--device-id` 死代码 bug**（见 §8） |
| CSV 副本 | 不入库，脚本从用户本地路径读取 |

## 2. 架构总览

```
                    ┌─────────────────────────────────────────┐
                    │            enviro-brain (单应用)          │
                    │                                           │
  cron 9:00/15:00   │  InspectionScheduler                      │
  ───────────────►  │    for scenario in {enviro, gangqu}:      │
                    │        inspectionService                  │
                    │          .executeInspection("auto", s)    │
                    │                                           │
  POST /trigger     │  InspectionController                    │
  ?scenario=gangqu  │    prepareInspection("manual", s)         │
                    │                                           │
                    │  InspectionService.runInspectionBody     │
                    │    findActiveByScenario(s)  ◄── camera_config(scenario) │
                    │    captureService.capture() ─► python camera_capture.py │
                    │         (海康 Artemis, 全局凭证, camera_code=indexCode) │
                    │    MinioStorageService.upload(prefix=s.minioPrefix)      │
                    │    camera_results INSERT                 │
                    │    FeishuNotifyService.send(scenario) ──► 按场景 webhook │
                    │    ledger / queqiao 回调                  │
                    └─────────────────────────────────────────┘

  camera_config:  scenario='enviro' | 'gangqu'
  inspection_records: scenario 列 + batch_id = {scenario}-{triggerType}-{date}-{HHmm}
  MinIO bucket scenes-camerapatrol:  enviro/...   gangqu/...
```

核心变化：原"全表读、无场景"的流水线，变为"按 scenario 过滤 + 按 scenario 选配置（飞书/MinIO 前缀）"。所有场景共用同一套抓拍、线程池、批量写、台账、鹊桥逻辑，仅分流点不同。

## 3. 数据模型变更

### 3.1 `camera_config` 加场景列（PostgreSQL，真实运行库）
```sql
ALTER TABLE camera_config
  ADD COLUMN scenario VARCHAR(32) NOT NULL DEFAULT 'enviro';
CREATE INDEX idx_camera_config_scenario ON camera_config(scenario);
```
- 现有环保摄像头 `scenario` 默认为 `'enviro'`，不受影响。
- 项目无 Flyway/Liquibase，此 SQL **手动在运行 PG 库执行**；同时需同步 `schema-h2.sql`（测试库）加同列。

### 3.2 `inspection_records` 加场景列
```sql
ALTER TABLE inspection_records
  ADD COLUMN scenario VARCHAR(32) NOT NULL DEFAULT 'enviro';
```
- 便于按场景统计 / 查询；`camera_results` 不单独加列（经 `record_id` 关联推导）。

### 3.3 实体
- `CameraConfig.java`：新增 `private String scenario;`（默认 `"enviro"`）。
- `InspectionRecord.java`：新增 `private String scenario;`。
- `CameraResult.java`：不加列（已知 `localScreenshotPath` 字段从未落库，勿依赖）。

### 3.4 Mapper（MyBatis）
- `CameraConfigMapper.xml`：
  - `insert` / `upsert`（含 `databaseId="postgresql"` 的 `ON CONFLICT` 分支）/ `update` 增加 `scenario` 列与值。
  - 新增 `findActiveByScenario(scenario, offset, size)`：`SELECT * FROM camera_config WHERE enabled = 1 AND scenario = #{scenario} ORDER BY id ASC LIMIT #{size} OFFSET #{offset}`。
  - `countByScenario(scenario)` 替代/补充 `countActive`。
- `InspectionRecordMapper.xml`：`insert` 增加 `scenario`。

### 3.5 `batch_id` 生成规则升级
原：`{triggerType}-{date}-{HHmm}`（如 `auto-2026-07-10-0900`）
新：`{scenario}-{triggerType}-{date}-{HHmm}`（如 `gangqu-auto-2026-07-10-0900`、`enviro-manual-2026-07-10-1430`）
- `InspectionService.prepareInspection(triggerType, scenario)` 内拼接。

## 4. 场景配置（application.yml）

新增 `enviro.scenarios` 配置块，按场景装配飞书 / MinIO 前缀 / 卡片文案：

```yaml
enviro:
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

- Java 侧用 `@ConfigurationProperties` 或 `Map<String, ScenarioConfig>` 绑定为一个 `ScenarioConfig` 对象集合，`InspectionService` / `FeishuNotifyService` / `MinioStorageService` 运行时按 `scenario` 取值。
- `ScenarioConfig` 字段：`feishuWebhookUrl`、`cardTitle`、`cardFooter`、`minioPrefix`。
- 环境变量 `GANGQU_FEISHU_WEBHOOK_URL` 在部署环境注入港区 webhook。

## 5. 巡检流水线改造（InspectionService）

- `prepareInspection(triggerType, scenario)`：
  - `cameras = cameraConfigService.findActiveByScenario(scenario, 1, 10000)`（替代原全表 `findActive`）。
  - `batchId = scenario + "-" + triggerType + "-" + date + "-" + HHmm`。
  - 写 `inspection_records` 时带 `scenario`。
- `executeInspection(triggerType, scenario)`（调度器用，`@Transactional`）、`runInspectionAsync(ctx, scenario)`（控制器用，`@Async`）：签名增加 `scenario`，透传至 `runInspectionBody`。
- `runInspectionBody` 内：
  - 抓拍、线程池(12)、超时(120s)、`camera_results` 批量写、台账、鹊桥回调：全部复用，无改动。
  - `feishuNotifyService.sendInspectionReport(record, results, scenario)`（见 §6）。
  - `minioStorageService.uploadScreenshot(cameraName, bytes, scenarioConfig.getMinioPrefix())`（见 §7）。
- 若某场景 `findActiveByScenario` 返回 0 路摄像头：调度器循环时**跳过该场景、不建空批次**（避免噪声记录）。

## 6. 飞书通知改造（FeishuNotifyService）

- `sendInspectionReport(InspectionRecord record, List<CameraResult> results, String scenario)`：
  - 按 `scenario` 取对应 `ScenarioConfig` 的 `feishuWebhookUrl` / `cardTitle` / `cardFooter`。
  - `webhookUrl` 为空则跳过（保持现有容错）。
  - `buildCardJson` 的标题、落款改为取自场景配置（不再写死"危废仓库"/"环保小脑"）。
- 港区 webhook 由 `GANGQU_FEISHU_WEBHOOK_URL` 注入，标题"智慧港区小脑巡检报告"、落款"智慧港区小脑"。

## 7. MinIO 改造（MinioStorageService）

- `uploadScreenshot(String cameraName, byte[] bytes, String prefix)`：object key 生成接受 `prefix` 参数：
  ```
  {prefix/}{yyyy-MM-dd}/{safeCameraName}_{HH}.jpg
  ```
  - 环保 prefix = `""` → `2026-07-10/name_HH.jpg`（行为不变）。
  - 港区 prefix = `"gangqu"` → `gangqu/2026-07-10/name_HH.jpg`。
- **清理兼容**：`MinioCleanupScheduler` 的 key 解析正则 `(\d{4}-\d{2}-\d{2}).*_(\d{2})\.jpg$` 仍能从带前缀的 key 中提取日期+小时，故保留 7 天 / 每日 02:00 策略对港区截图直接生效，不会被漏清或误清。
- bucket 不变（`scenes-camerapatrol`），全局 `retention-days` / `cleanup.cron` 复用。

## 8. 附带修复：artemisDeviceId / --device-id 死代码 bug

- 现状（已读源码核实）：`CaptureService.buildCommand()`（第 102–104 行）在 `artemisDeviceId` 非空时往命令加 `--device-id <值>`；但 `scripts/camera_capture.py` 的 argparse（553–564 行）**无 `--device-id` 定义**，且取流只用 `--camera-code`（第 573 行 `camera_index_code=args.camera_code`）。后果：有 device-id 的摄像头抓拍进程 argparse 报错 `unrecognized arguments` 非零退出 → 标记离线。
- **修复**：删除 `CaptureService.buildCommand()` 第 102–104 行那段 `if (artemisDeviceId != null && !empty) { cmd.add("--device-id"); cmd.add(...) }`。
  - 该参数全代码路径未被消费，属死代码，删除零风险。
  - 删除后，即使误填 `artemisDeviceId` 也不会再崩；可顺带排查环保侧是否已有摄像头因此静默失败。
- 港区导入时 `artemisDeviceId` 留空（CSV 无此字段），本就不触发该路径；修复后双保险。

## 9. 港区摄像头录入（一次性脚本）

- 新增 `scripts/import_gangqu_cameras.py`：
  - 参数：`--csv <港区CSV路径>`（默认指向用户本地 `C:\Users\7even\Downloads\港区小脑摄像头清单.csv`，但需用户显式传入或确认路径）。
  - 读取 CSV（UTF-8-sig，列：编码=camera_code、名称=camera_name）。
  - 幂等写入 `camera_config`：`INSERT ... ON CONFLICT (camera_code) DO UPDATE SET camera_name=EXCLUDED.camera_name, scenario='gangqu', enabled=1`。
  - 固定值：`scenario='gangqu'`、`enabled=1`、`artemisDeviceId=''`（空，规避 §8 bug）、`enterprise`/`location`/`rtsp_url` 给默认值或空串。
  - 复用 `view_pg.py` 同一套 psycopg2 DSN（环境变量 `PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD` 可覆盖）。
- 用户**运行一次**即可完成录入；**CSV 不入库**，脚本从本地路径读取。
- 验证：运行后 `SELECT count(*) FROM camera_config WHERE scenario='gangqu'` 应为 13（与 CSV 路数一致）。

## 10. 触发入口改造

### 10.1 定时（InspectionScheduler）
```java
@Scheduled(cron = "${enviro.inspection.cron:0 0 9,15 * * ?}")
public void scheduledInspection() {
    for (String scenario : scenarioConfigs.keySet()) {
        if (cameraConfigService.countByScenario(scenario) == 0) continue;
        inspectionService.executeInspection("auto", scenario);
    }
}
```
- 保留单 cron（9:00/15:00），循环所有配置场景各跑一批；0 路场景跳过。
- 港区与环保各自独立 `batch_id` 前缀，互不混。

### 10.2 手动（InspectionController）
```java
@PostMapping("/api/v1/inspections/trigger")
public ... trigger(@RequestParam(required = false) String scenario) {
    String sc = (scenario == null || scenario.isBlank()) ? "enviro" : scenario;
    InspectionContext ctx = inspectionService.prepareInspection("manual", sc);
    inspectionService.runInspectionAsync(ctx, sc);
    return accepted().body(...);
}
```
- `?scenario=gangqu` 触发港区；不传默认 `'enviro'`，保持旧调用方兼容。

## 11. 测试

- `src/main/resources/db/schema-h2.sql`：补 `camera_config.scenario`、`inspection_records.scenario` 列（测试库一致）。
- `CameraConfigServiceTest`：新增"按 scenario 过滤"用例（enviro vs gangqu 互不串）。
- `InspectionService` 测试：新增"按 scenario 成批、batch_id 带前缀、0 路场景跳过"用例；mock `MinioStorageService` / `FeishuNotifyService` 校验按场景分流的 prefix / webhook。
- 测试库预置 enviro + gangqu 两组摄像头，验证 `InspectionScheduler` 循环触发与分流。
- `CaptureService` 单测：验证删除 `--device-id` 逻辑后，有/无 `artemisDeviceId` 时命令均不含 `--device-id`。

## 12. 文档

- 更新 `docs/enviro-brain-database.md`：补 `scenario` 列说明（如该文档存在且为权威；若与运行 PG 库不一致以运行库为准）。
- 本设计文档提交至 `docs/superpowers/specs/2026-07-10-gangkou-integration-design.md`。

## 13. 实施注意 / 风险

1. **无 migration 工具**：`ALTER TABLE` 需手动在运行 PG 库执行；务必先备份/确认连接的是 `smartpark_scenes_zhh`。
2. **`schema.sql` 是 MySQL、不可作准**；真实 schema 以运行 PG 库 + 实体/Mapper 为准；测试库以 `schema-h2.sql` 为准。
3. **`local_screenshot_path` 字段从未落库**：台账如需港区截图，走 MinIO URL，勿依赖该字段。
4. **`CameraResult`/`inspection_records` 加列需在运行库执行 ALTER**，并同步 Mapper insert。
5. **双实例风险**：部署主机上旧的 `inspection-enviro-brain` docker 容器若仍在跑，会和 Maven 实例双跑抢 cron；接入后更应停掉旧的，避免港区/环保批次重复。
6. **webhook 注入**：`GANGQU_FEISHU_WEBHOOK_URL` 需在部署环境（env / docker-compose `environment`）配置，否则港区飞书被跳过。

## 14. 验收标准

- [ ] 运行 PG 库 `camera_config` 有 13 条 `scenario='gangqu'` 记录，且 `artemisDeviceId` 为空。
- [ ] 9:00/15:00 cron 触发后，`inspection_records` 出现 `gangqu-auto-...` 与 `enviro-auto-...` 两类批次，互不混。
- [ ] 港区截图出现在 MinIO `gangqu/` 前缀下，7 天后被清理。
- [ ] 港区飞书卡片推到专用 webhook，标题"智慧港区小脑巡检报告"。
- [ ] `POST /api/v1/inspections/trigger?scenario=gangqu` 可手动触发港区批次。
- [ ] `CaptureService` 不再拼接 `--device-id`，有/无 `artemisDeviceId` 的摄像头均正常抓拍。
- [ ] 现有环保巡检行为完全不变（回归通过）。
