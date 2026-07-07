# Phase 3 实施任务清单（鹊桥数据同步层）

> 所属层：全部为 `queqiao`（新建独立模块）。测试策略遵循 TDD：先写失败测试，再实现使其通过。
> 构建/测试命令（Windows Git Bash）：
> ```bash
> export JAVA_HOME="/d/jdk-17_windows-x64_bin/jdk-17.0.4.1"
> export MAVEN_HOME="/d/apache-maven-3.9.11/apache-maven-3.9.11"
> export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"
> cd queqiao && mvn -B test -Dmaven.repo.local="D:/gkproject/camera-inspection3.0/.m2/repository"
> ```

## 0. 项目骨架

- [x] 0.1 (queqiao) 创建 `queqiao/` Maven 模块：`pom.xml`（groupId `com.queqiao`、Spring Boot 3.3.5、mybatis、mysql、h2-test、lombok、actuator），与 `enviro-brain` 同构
- [x] 0.2 (queqiao) 创建启动类 `com.queqiao.sync.QueqiaoSyncApplication` 与 `application.yml` / `application-dev.yml`（独立端口 8081、独立数据源 `queqiao_sync`、配置 `enviro-brain.base-url` / `enviro-brain.api-key` / `queqiao.sync.cron`=每30分钟 / `queqiao.sync.batch-limit`=200 / `queqiao.notify.api-key`）
- [x] 0.3 (queqiao) 创建 `src/test/resources/application-test.yml`（H2 MySQL 模式 + schema 初始化）与测试运行辅助

## 1. 数据库 Schema（queqiao）

- [x] 1.1 (queqiao) `src/main/resources/db/schema.sql`：建 4 张表 `synced_inspection_records` / `synced_camera_results` / `synced_ledger_records`（主键=环保小脑原始 `id`、含 `synced_at`、镜像字段）与 `sync_watermark`（`table_name` 唯一、`last_sync_version`、`last_sync_time`），含全部索引
- [x] 1.2 (queqiao) `src/test/resources/db/schema-h2.sql`：H2 等价 DDL（兼容 `idx_*` 索引定义，H2 MySQL 模式下 `INSERT ... ON DUPLICATE KEY UPDATE` 语法可用）

## 2. 客户端 DTO 与同步客户端（queqiao）

- [x] 2.1 (queqiao) 创建 `dto/` 包：`ApiResponse<T>`、`SyncResponse<T>`（含 `hasMore`、`nextSince`）、`WatermarkResponse`（`watermark`、`serverTime`）、`InspectionRecordDto` / `CameraResultDto` / `LedgerRecordDto`（字段 1:1 镜像环保小脑实体）
- [x] 2.2 (queqiao) 创建 `EnviroBrainSyncClient`（RestTemplate），方法：`getWatermark()`、`syncInspections(since,limit)`、`syncCameraResults(since,limit)`、`syncLedgerRecords(since,limit)`，均带 `X-API-Key` 头
- [x] 2.3 (queqiao) `EnviroBrainSyncClientTest`：用 `MockRestServiceServer` 模拟环保小脑 HTTP 响应，验证鉴权头、URL、JSON 反序列化（含 `watermark` / `SyncResponse` / `nextSince` 字段正确性）

## 3. 实体与幂等 Mapper（queqiao）

- [x] 3.1 (queqiao) 创建 `entity/`：`SyncedInspectionRecord`、`SyncedCameraResult`、`SyncedLedgerRecord`、`SyncWatermark`
- [x] 3.2 (queqiao) 创建 Mapper 接口 + XML：`SyncedInspectionRecordMapper` / `SyncedCameraResultMapper` / `SyncedLedgerRecordMapper`（方法 `upsert(record)` 使用 `INSERT ... ON DUPLICATE KEY UPDATE` 主键=id）、`SyncWatermarkMapper`（`selectByTableName`、`upsert`）
- [x] 3.3 (queqiao) `Synced*MapperTest`：用 H2 验证 `upsert` 幂等性（同 `id` 二次写入行数不增加、业务字段被更新、`synced_at` 刷新）

## 4. 同步编排（queqiao）

- [x] 4.1 (queqiao) 创建 `SyncOrchestrationService.syncOnce()`：① 拉取 `watermark` → ② 读取本地三表水位 → ③ 比对，无水新跳过 → ④ 逐表 `nextSince` 游标循环拉取+幂等写入 → ⑤ 更新 `sync_watermark`
- [x] 4.2 (queqiao) 实现游标防护：当某表 `hasMore=true` 且 `nextSince <= since` 时终止该表拉取并告警，避免死循环（设计 D3/R1）
- [x] 4.3 (queqiao) 失败容错：单表异常捕获记日志继续其他表；整轮异常不更新水位（设计 D6）
- [x] 4.4 (queqiao) `SyncOrchestrationServiceTest`：用 H2 + MockBean 客户端，验证（a）有增量时拉取并写库+推进水位；（b）无新数据跳过；（c）幂等不增行；（d）单表失败不影响其他表；（e）游标退化防护终止

## 5. 定时调度（queqiao）

- [x] 5.1 (queqiao) 创建 `scheduler/SyncScheduler`：`@Scheduled(cron="${queqiao.sync.cron}")` 每 30 分钟调用 `syncOnce()`
- [x] 5.2 (queqiao) `SyncSchedulerTest`：`@SpyBean` 验证定时触发（用测试 profile 缩短 cron 或手动调用方法验证编排被调用）

## 6. 回调接收端点（queqiao，可选）

- [x] 6.1 (queqiao) 创建 `config/ApiKeyAuthInterceptor` + `WebMvcConfig`，保护 `/api/notify/**` 校验 `X-API-Key`（`queqiao.notify.api-key`），失败 401
- [x] 6.2 (queqiao) 创建 `controller/NotifyController`：`POST /api/notify/new-data` 接收 `{"syncVersion","type"}`，校验通过后立即触发 `syncOnce()`，异常隔离不抛 500
- [x] 6.3 (queqiao) `NotifyControllerTest`：验证（a）正确 Key 触发同步并返回 2xx；（b）错误 Key 返回 401 不触发；（c）同步异常仍返回 2xx

## 7. 最终验证

- [x] 7.1 (queqiao) 全量测试通过：`mvn -B test`（所有 queqiao 测试 PASS）
- [x] 7.2 (queqiao) `mvn -B package` BUILD SUCCESS
- [x] 7.3 更新 `tasks.md` 勾选状态，并补充 `docs/` 中 Phase 3 实施纪要（如需要）
