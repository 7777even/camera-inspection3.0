# Phase 3：鹊桥数据同步层 — 实施纪要

> 编写日期：2026-07-07 ｜ 模块：`queqiao` ｜ 关联 OpenSpec change：`phase3-queqiao-sync-layer`（已归档）
> 三层架构：环保小脑 `enviro-brain`（数据源）→ **鹊桥 `queqiao`（同步层）** → 脑机桌面端（消费方）

## 1. 目标与定位

鹊桥是环保小脑三层架构中的**数据同步层**，夹在「环保小脑」与「脑机桌面端」之间：

- **上游**：环保小脑 `enviro-brain` 对外暴露增量同步 REST 接口（`/api/v1/sync/*`）与全局水位（`watermark`）。
- **自身**：按 `sync_version` 游标增量拉取巡检记录 / 摄像头结果 / 台账记录，幂等写入自有 MySQL 镜像库，并维护每张表的同步水位。
- **下游**：脑机桌面端消费鹊桥镜像库的数据；环保小脑在产生新数据后，通过回调端点主动触发鹊桥再同步。

## 2. 架构概览

```
            enviro-brain (上游, 8080)
                │  GET /api/v1/sync/watermark
                │  GET /api/v1/sync/{inspections|camera-results|ledger-records}?since=&limit=
                ▼
        ┌─────────────────────────────────────────┐
        │  EnviroBrainSyncClient  (RestTemplate+X-API-Key)
        │                    │
        │                    ▼
        │        SyncOrchestrationService.syncOnce()
        │        (游标循环 + 退化防护 + 单表容错 + 水位持久化)
        │              │              │
        │     Mapper(upsert)      SyncWatermarkMapper
        │              │              │
        │              ▼              ▼
        │        MySQL 镜像库  ◀── sync_watermark
        └─────────────────────────────────────────┘
           ▲                              │
           │ POST /api/notify/new-data   │ @Scheduled(cron 每30分钟)
           │   (X-API-Key 校验)           ▼
        enviro-brain                SyncScheduler
        (回调触发)
```

- `SyncScheduler`（定时）与 `NotifyController`（回调）都只调用 `SyncOrchestrationService.syncOnce()`，编排逻辑单一收口。
- `ApiKeyAuthInterceptor` 保护 `/api/notify/**`，失败返回 401。

## 3. 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Spring Boot | 3.3.5 |
| Java | 17 |
| MyBatis | 3.0.3（mybatis-spring-boot-starter） |
| 生产库 | MySQL 5.7 |
| 测试库 | H2（`MODE=MYSQL`，内存） |
| Lombok / Actuator | 沿用 enviro-brain 同构依赖 |

## 4. 模块结构与核心组件

### 4.1 包结构（27 个主类）

- `dto/`：`ApiResponse<T>`、`SyncResponse<T>`（含 `hasMore`/`nextSince`，继承 ApiResponse）、`WatermarkResponse`、`InspectionRecordDto`/`CameraResultDto`/`LedgerRecordDto`（1:1 镜像）、`NotifyRequest`
- `client/`：`EnviroBrainSyncClient` — RestTemplate 封装，4 个接口（`getWatermark`、`syncInspections`、`syncCameraResults`、`syncLedgerRecords`），均带 `X-API-Key` 头
- `entity/`：`SyncedInspectionRecord`、`SyncedCameraResult`、`SyncedLedgerRecord`、`SyncWatermark`
- `mapper/`：4 个 Mapper 接口 + XML（`upsert` 默认用 H2 兼容的 `MERGE INTO`，`databaseId="mysql"` 版用 `ON DUPLICATE KEY UPDATE`）
- `service/`：`SyncOrchestrationService`（核心 `syncOnce()`）、`SyncSummary`
- `scheduler/`：`SyncScheduler`（`@Scheduled` cron）
- `controller/`：`NotifyController`（`POST /api/notify/new-data`）
- `config/`：`RestTemplateConfig`、`ApiKeyAuthInterceptor`、`WebMvcConfig`、`DatabaseConfig`
- `exception/`：`SyncClientException`（隔离同步失败）

### 4.2 核心流程 `syncOnce()`

```
① 拉取 enviro-brain watermark（全局最大 sync_version）
② 读取本地三表水位（sync_watermark）
③ 比对：若本地水位 >= 远端水位 → 无新数据，跳过
④ 逐表以 nextSince 游标循环拉取 + 幂等 upsert，直到 hasMore=false 或退化
⑤ 更新 sync_watermark
```

**三道防护（设计 D3/R1、D6）：**
- **游标退化防护**：当某表 `hasMore=true` 且 `nextSince <= since` 时，终止该表拉取并告警，避免死循环。
- **单表容错**：单表异常捕获记日志，继续其他表。
- **整轮容错**：整轮异常不更新水位（下次重试从原水位继续）。

### 4.3 关键配置项

| 配置 | 值 | 说明 |
|---|---|---|
| `server.port` | 8081 | 鹊桥独立端口 |
| `enviro-brain.base-url` | http://... | 上游地址 |
| `enviro-brain.api-key` | — | 调用上游的 X-API-Key |
| `queqiao.sync.cron` | `0 */30 * * * *` | 每 30 分钟定时同步 |
| `queqiao.sync.batch-limit` | 200 | 单批拉取上限 |
| `queqiao.notify.api-key` | — | 校验 `/api/notify/**` 的 X-API-Key |

> 注：`application-dev.yml` 含真实库连接与 Key，被 `.gitignore` 忽略，**每台部署机自带**，不入库。

## 5. 数据库设计

4 张表（MySQL `schema.sql` + H2 `schema-h2.sql`）：

- `synced_inspection_records` / `synced_camera_results` / `synced_ledger_records`：主键 = 环保小脑原始 `id`，含 `synced_at` 镜像字段，逐表建 `idx_sync_version` 等索引。
- `sync_watermark`：`table_name` 唯一，记录 `last_sync_version BIGINT` 与 `last_sync_time`，用于断点续拉。

## 6. 测试策略与结果（TDD，21 用例全绿）

9 个测试类，全部基于 H2（`MODE=MYSQL`）+ Mock：

| 测试类 | 用例 | 覆盖点 |
|---|---|---|
| `EnviroBrainSyncClientTest` | 4 | MockRestServiceServer：X-API-Key 头、watermark/SyncResponse/nextSince 解析、5xx 抛异常 |
| 4× MapperTest（`@MybatisTest`） | 各 2 | 首次插入 + `upsert` 幂等性（同 id 二次写入行数不增、字段被更新） |
| `SyncOrchestrationServiceTest` | 6+ | 增量推进 / 无新数据跳过 / 幂等不增行 / 单表失败隔离 / 游标退化防护（@SpringBootTest + MockBean） |
| `SyncSchedulerTest` | 1 | `@SpyBean` 验证定时触发调用 `syncOnce()` |
| `NotifyControllerTest` | 3 | 正确 Key→200 / 错误 Key→401 / 同步异常仍返回 200（异常隔离） |

**验证结果：**
- `mvn test` → `Tests run: 21, Failures: 0, Errors: 0, BUILD SUCCESS`
- `mvn package` → `BUILD SUCCESS`，产出 `queqiao/target/queqiao-1.0.0-SNAPSHOT.jar`（30MB 可执行 Spring Boot jar）

## 7. 开发过程中发现并修复的关键问题（重点）

Phase 3 的测试构建一度反复失败，逐一定位修复，以下 5 点是核心价值沉淀：

### 7.1 `EnviroBrainSyncClient` 异常未转换
`exchange()` 原未捕获 `RestTemplate` 默认抛出的 `RestClientException`，导致 4xx/5xx 直接以 `HttpServerErrorException` 冒泡，**绕过** `SyncClientException` 的统一失败语义。
**修复：** 包 `try { ... } catch (RestClientException e) { throw new SyncClientException(...); }`。

### 7.2 共享 H2 库建表撞车（最隐蔽）
`@MybatisTest` 默认把 datasource 替换成 plain H2，但 `ON DUPLICATE KEY UPDATE` 需要 MySQL 模式；为测该语句强行 `@AutoConfigureTestDatabase(replace = NONE)` 共用 `queqiaotest` 库（`DB_CLOSE_DELAY=-1` 使 JVM 内共享），结果第二个测试上下文建表报 `Table "synced_inspection_records" already exists`。
**修复：** 新建 `AbstractQueqiaoTest` 基类，用 `@DynamicPropertySource` + `AtomicInteger` 计数器给**每个测试上下文分配独立 H2 库名**，彻底隔离。

### 7.3 H2 不支持 `NOW()`
MySQL 的 `NOW()` 在 H2 下报函数不存在。
**修复：** 全部改为 `CURRENT_TIMESTAMP`（MySQL / H2 双兼容）。

### 7.4 H2 不支持 `ON DUPLICATE KEY UPDATE`
H2 无该语法。
**修复：** 将 Mapper 默认 `upsert` 语句改为 H2 兼容的 **`MERGE INTO`**（以 `id` / `table_name` 为幂等键），单独保留 `databaseId="mysql"` 版本的 `ON DUPLICATE KEY UPDATE`；MyBatis 按实际库类型自动选语句。

### 7.5 测试断言与 Lombok
- `isSameAs` 断言对二次查询失败（DB 返回新实例）→ 改为 `isEqualTo`。
- `SyncResponse extends ApiResponse` 用 Lombok `@Data` 报 "without a call to superclass" → 加 `@EqualsAndHashCode(callSuper = true)`。

## 8. 构建与工具链（Windows 沙箱坑）

Git Bash 下 `mvn` 脚本报 `ClassNotFoundException`（MINGW 路径展开问题）；`cmd /c` 不执行内联命令；PowerShell 破坏 `-D` 参数里的 `:`。
**解决方案（已沉淀为可复用 skill `maven-windows-build`）：** 用 PowerShell 调 `mvn.cmd`，配合 `--%` 停止参数解析，并使用项目级 `ci-settings.xml` 指向本地 `.m2/repository`：

```powershell
cd D:\gkproject\camera-inspection3.0\queqiao
mvn.cmd --% -o -B test -s D:\gkproject\camera-inspection3.0\queqiao\ci-settings.xml
```

## 9. OpenSpec 流程与归档

- 4 个新 capability：`queqiao-sync-schema` / `-client` / `-scheduler` / `-notify-receiver`（共 19 个 Requirement + Scenario）。
- change `phase3-queqiao-sync-layer` 已归档：`openspec/changes/archive/2026-07-07-phase3-queqiao-sync-layer/`。
- **主规格同步（后续补充）：** 因 Phase 1/2 归档时未做 spec 同步，主规格 `openspec/specs/` 原为空。按用户确认执行「选项 A」，将 phase1(6) + phase3(4) 共 **10 个 capability 规格**同步到主规格，并修正了增量规格→主规格的格式差异（剥除 `## ADDED Requirements` 的 `ADDED` 前缀、为 phase1 规格补 `# Capability:` 标题与 `## Purpose` 段）。最终 `openspec validate --specs` = **10 passed / 0 failed**。

## 10. 验证结果汇总

| 维度 | 结果 |
|---|---|
| 单元测试 | 21 绿，0 失败 0 错误 |
| 打包 | BUILD SUCCESS，jar 30MB |
| OpenSpec change | 已归档 |
| 主规格 | 10 capability 全部校验通过 |
| 提交 | 本地 3 commit（feat 模块 / chore 归档 / chore 主规格同步） |

## 11. 已知限制与后续

- **未做真实端到端冒烟**：Phase 3 测试仅用 `MockRestServiceServer` + H2，未对真实 `enviro-brain` 端点 + MySQL 跑通。建议 Phase 5 验收时补一次端到端验证（Phase 1/2 当时靠冒烟各揪出 2 个真 bug）。
- **配置本地化**：`application-dev.yml` 不入库，部署机各自提供。
- **后续阶段**：
  - **Phase 4 — 鹊桥 MCP 封装**：把同步能力包成 MCP 工具，供脑机桌面端调用。
  - **Phase 5 — 验收上线**：端到端验收 + 部署。

---

*附：本纪要对应 tasks.md 中 7.3「补充 docs/ 中 Phase 3 实施纪要」；Phase 3 全部 23 个任务已勾选，模块已测试、打包、归档并本地提交。*
