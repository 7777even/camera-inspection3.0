# 环保危废仓库摄像头自动巡检 — 环保小脑 + 鹊桥数据同步 + MCP 落地设计方案 v3.0

> **方案定位**：环保小脑每小时执行摄像头巡检，结果入库并推送飞书 Webhook；鹊桥平台作为**独立数据代理层**，从环保小脑主动同步数据到自有存储，再通过 MCP 工具供脑机桌面端查询巡查台账。**脑机桌面端不直接调用环保小脑，所有数据请求由鹊桥响应。**
>
> **架构关键词**：环保小脑（Java 定时每小时 + 入库 + 飞书）→ 鹊桥平台（数据同步 + 自有存储 + MCP）→ 脑机桌面端
>
> **v3.0（2026-07-02）**：基于 v2.0 架构调整——鹊桥从"透传代理"升级为"数据同步层"，自有存储、自有响应；环保小脑定时频率从每日改为每小时；脑机端与环保小脑完全解耦。

---

## 一、总体架构概览

### 1.1 架构演进对比

| 维度 | v2.0 方案 | v3.0 新方案 |
|------|-----------|------------|
| **环保小脑执行频率** | 每日 15:00 一次 | **每小时自动执行** |
| **鹊桥角色** | HTTP 透传代理（调环保小脑 API） | **独立数据代理层**（主动同步、自有存储、自主响应） |
| **数据存储** | 仅环保小脑 MySQL | 环保小脑 MySQL **+ 鹊桥自有数据库** |
| **脑机端数据来源** | 直接穿透环保小脑 | **仅从鹊桥获取**，不直接调环保小脑 |
| **环保小脑对外暴露** | REST API（供鹊桥实时调用） | **数据同步接口**（供鹊桥拉取增量数据） |
| **飞书通知** | 环保小脑直接推送 | 环保小脑直接推送（不变） |
| **鹊桥依赖环保小脑** | 强依赖（每次查询都穿透） | **弱依赖**（同步失败时仍可返回最近一次同步的历史数据） |

### 1.2 整体架构图

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         脑机桌面端（调用层）                                  │
│                                                                              │
│   「帮我查一下今天危废仓库的巡查台账」                                         │
│   「哪些摄像头昨天离线了？」「生成本周的台账报告」                               │
└────────────────────────────┬───────────────────────────────────────────────┘
                             │ 自然语言 → MCP 工具调用
                             │ ⚠ 只访问鹊桥，不直接访问环保小脑
                             ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                    鹊桥平台（数据同步层 + MCP 封装层）                         │
│                                                                              │
│   ┌───────────────────────────────────────────────────────────────┐        │
│   │                    数据同步模块                                  │        │
│   │   · 定时拉取（每 30min）：从环保小脑同步增量数据                   │        │
│   │   · 事件驱动（可选）：环保小脑入库后回调通知鹊桥                    │        │
│   │   · 写入鹊桥自有数据库（MySQL / PostgreSQL）                     │        │
│   │   · 同步失败容错：返回最近一次成功同步的历史数据                    │        │
│   └───────────────────────────────────────────────────────────────┘        │
│                                                                              │
│   ┌───────────────────────────────────────────────────────────────┐        │
│   │                    MCP 工具模块                                  │        │
│   │   · get_inspection_ledger    获取巡查台账                        │        │
│   │   · get_camera_status        查询摄像头状态                      │        │
│   │   · get_inspection_summary   获取统计摘要                        │        │
│   │   · trigger_inspection       手动触发巡检（转发环保小脑）          │        │
│   │   · download_ledger_docx     下载台账 docx 文件                  │        │
│   │                                                                  │        │
│   │   ⚠ 查询类工具从鹊桥自有数据库直接响应                              │        │
│   │   ⚠ 操作类工具（触发/下载）转发环保小脑                             │        │
│   └───────────────────────────────────────────────────────────────┘        │
│                                                                              │
│   ┌───────────────────────────────────────────────────────────────┐        │
│   │                    鹊桥自有数据库                                 │        │
│   │   · synced_inspection_records                                  │        │
│   │   · synced_camera_results                                      │        │
│   │   · synced_ledger_records                                      │        │
│   └───────────────────────────────────────────────────────────────┘        │
└────────────────────────────────────────────────────────────────────────────┘
                             │ 数据同步（定时拉取 / 事件回调）
                             │ ⚠ 鹊桥是唯一消费者，脑机端不直接访问此接口
                             ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                   环保小脑（Java Spring Boot 服务）                            │
│                                                                              │
│   ┌───────────────────────────────────────────────────────────────┐        │
│   │   定时任务模块（每小时自动执行）                                    │        │
│   │   @Scheduled(cron = "0 0 * * * ?")                              │        │
│   │                                                                  │        │
│   │   1. 读摄像头清单（DB/Excel）                                      │        │
│   │   2. 调 Python 截图脚本（ProcessBuilder）                          │        │
│   │   3. 质量判定 → 结果入库（MySQL）                                  │        │
│   │   4. 生成 docx 台账 → 入库 + 文件存储                              │        │
│   │   5. 推送飞书 Webhook 通知                                        │        │
│   └───────────────────────────────────────────────────────────────┘        │
│                                                                              │
│   ┌───────────────────────────────────────────────────────────────┐        │
│   │   数据同步接口模块（供鹊桥拉取）                                    │        │
│   │   GET /api/v1/sync/inspections?since=...    增量巡检记录          │        │
│   │   GET /api/v1/sync/camera-results?since=... 增量摄像头结果        │        │
│   │   GET /api/v1/sync/ledger-records?since=... 增量台账记录          │        │
│   │   POST /api/v1/inspections/trigger          手动触发（鹊桥转发）  │        │
│   │   GET /api/v1/ledger/{id}/download          台账文件下载（鹊桥转发）│        │
│   └───────────────────────────────────────────────────────────────┘        │
│                                                                              │
│   ┌───────────────────────────────────────────────────────────────┐        │
│   │   数据层：MySQL                                                  │        │
│   │   · inspection_records                                          │        │
│   │   · camera_results                                              │        │
│   │   · ledger_records                                              │        │
│   └───────────────────────────────────────────────────────────────┘        │
└────────────────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                        底层基础设施                                           │
│                                                                              │
│   摄像头 RTSP 流     海康 Artemis API     飞书 Webhook     MySQL 数据库      │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、核心设计原则

### 2.1 数据流向总原则

```
写路径（环保小脑自治）：
    环保小脑每小时定时 → 截图 → 入库 → 生成台账 → 推飞书
    （完全自治，不依赖鹊桥）

同步路径（鹊桥主动拉取）：
    鹊桥定时/回调 → 拉环保小脑增量数据 → 写入鹊桥自有数据库
    （鹊桥是数据消费者，环保小脑是数据生产者）

读路径（鹊桥自主响应）：
    脑机桌面端 → 鹊桥 MCP → 鹊桥自有数据库 → 返回结果
    （查询类请求不穿透环保小脑）

操作路径（鹊桥转发）：
    脑机桌面端 → 鹊桥 MCP trigger_inspection → 转发环保小脑 API
    （手动触发等操作类请求，鹊桥转发到环保小脑执行）
```

### 2.2 鹊桥作为数据代理层的关键职责

| 职责 | 说明 |
|------|------|
| **数据同步** | 定时从环保小脑拉取增量数据，写入自有数据库 |
| **数据响应** | 脑机端查询类请求，鹊桥从自有数据库直接响应 |
| **操作转发** | 手动触发巡检、下载文件等操作类请求，鹊桥转发到环保小脑 |
| **容错兜底** | 环保小脑不可用时，鹊桥仍可返回最近一次同步的历史数据 |
| **数据加工** | 鹊桥可对同步来的数据做聚合、统计、格式化等二次加工 |

### 2.3 环保小脑与鹊桥的职责边界

| 职责 | 环保小脑 | 鹊桥平台 |
|------|---------|----------|
| 定时巡检执行 | ✅ 每小时自动执行 | ❌ 不参与 |
| 截图 + 质量判定 | ✅ Python 脚本 + ProcessBuilder | ❌ 不参与 |
| 数据入库 | ✅ 写 MySQL | ✅ 同步后写自有数据库 |
| 台账 docx 生成 | ✅ Apache POI 生成 | ❌ 不参与（仅存储文件引用） |
| 飞书通知 | ✅ Webhook 推送 | ❌ 不参与 |
| 数据同步接口 | ✅ 提供增量查询 API | ✅ 定时拉取 |
| 台账查询响应 | ❌ 不直接服务脑机端 | ✅ 从自有数据库响应 |
| MCP 工具封装 | ❌ | ✅ 封装为 MCP 工具 |
| 手动触发转发 | ✅ 提供触发 API | ✅ MCP 转发调用 |

---

## 三、环保小脑 Java 服务设计

### 3.1 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 框架 | **Spring Boot 2.x / 3.x** | 标准 Java 服务框架 |
| 定时任务 | **Spring `@Scheduled`**（每小时） | `cron = "0 0 * * * ?"` |
| ORM | **MyBatis** | SQL 直观可控 |
| 数据库 | **MySQL 8.x** | 环保小脑自有数据存储 |
| HTTP 客户端 | **RestTemplate** / **OkHttp** | 调海康 API + 飞书 Webhook |
| docx 生成 | **Apache POI**（`poi-ooxml`） | Java 标准 docx 方案 |
| 截图能力 | **Python 脚本**（Java ProcessBuilder 调用） | 复用 v1.6 核心代码 |
| 认证 | **API Key**（Header: `X-API-Key`） | 内部平台集成 |
| 配置 | **`application.yml`** | Spring Boot 标准配置 |

### 3.2 项目结构

```
enviro-brain/                                # Spring Boot 项目
├── src/main/java/com/enviro/brain/
│   ├── EnviroBrainApplication.java          # 启动入口
│   │
│   ├── config/
│   │   ├── ApiKeyAuthConfig.java            # API Key 认证拦截器
│   │   └── WebMvcConfig.java                # MVC 配置
│   │
│   ├── scheduler/
│   │   └── InspectionScheduler.java         # @Scheduled 每小时执行
│   │
│   ├── service/
│   │   ├── InspectionService.java           # 巡检业务逻辑（核心编排）
│   │   ├── CameraService.java               # 摄像头信息查询
│   │   ├── LedgerService.java               # 台账查询与生成
│   │   ├── FeishuNotifyService.java         # 飞书通知推送
│   │   ├── CaptureService.java              # 调用 Python 截图脚本
│   │   └── DocxGeneratorService.java        # Apache POI 生成台账 docx
│   │
│   ├── controller/
│   │   ├── SyncController.java              # ⭐ 数据同步接口（供鹊桥拉取）
│   │   ├── InspectionController.java        # 手动触发巡检（鹊桥转发调用）
│   │   └── LedgerController.java            # 台账文件下载（鹊桥转发调用）
│   │
│   ├── mapper/
│   │   ├── InspectionRecordMapper.java
│   │   ├── CameraResultMapper.java
│   │   └── LedgerRecordMapper.java
│   │
│   ├── entity/
│   │   ├── InspectionRecord.java
│   │   ├── CameraResult.java
│   │   └── LedgerRecord.java
│   │
│   └── dto/
│       ├── SyncResponse.java                # ⭐ 增量同步响应 DTO
│       ├── TriggerRequest.java
│       └── ApiResponse.java
│
├── src/main/resources/
│   ├── application.yml
│   ├── mapper/
│   │   ├── InspectionRecordMapper.xml
│   │   ├── CameraResultMapper.xml
│   │   └── LedgerRecordMapper.xml
│   └── templates/
│       └── 危废仓库巡查台账_新模版.docx
│
├── scripts/
│   └── camera_capture.py                    # Python 截图脚本（复用 v1.6）
│
└── pom.xml
```

### 3.3 数据库设计（环保小脑 MySQL）

三张核心表与 v2.0 一致，新增 `sync_version` 字段支持鹊桥增量同步：

#### 3.3.1 巡检记录表

```sql
CREATE TABLE inspection_records (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    inspect_date    DATE NOT NULL COMMENT '巡检日期',
    inspect_time    DATETIME NOT NULL COMMENT '精确执行时间',
    total_count     INT COMMENT '总摄像头数',
    online_count    INT COMMENT '在线数量',
    offline_count   INT COMMENT '离线数量',
    abnormal_count  INT COMMENT '异常数量',
    ledger_count    INT COMMENT '登记台账数量',
    trigger_type    VARCHAR(20) DEFAULT 'auto' COMMENT 'auto/manual',
    status          VARCHAR(20) DEFAULT 'completed' COMMENT '执行状态',
    sync_version    BIGINT DEFAULT 0 COMMENT '⭐ 同步版本号，每次入库递增',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_inspect_date (inspect_date),
    INDEX idx_sync_version (sync_version)      # ⭐ 鹊桥增量拉取用
) COMMENT='巡检记录表';
```

#### 3.3.2 摄像头巡检结果表

```sql
CREATE TABLE camera_results (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    inspect_id          BIGINT NOT NULL COMMENT '关联巡检记录',
    camera_code         VARCHAR(64) NOT NULL COMMENT '海康摄像头编码',
    camera_name         VARCHAR(128) COMMENT '监控点名称',
    enterprise          VARCHAR(128) COMMENT '所属企业',
    status              VARCHAR(20) COMMENT 'online/offline/abnormal',
    quality_score       DECIMAL(4,2) COMMENT '质量评分 0~1',
    quality_detail      TEXT COMMENT 'JSON: laplacian/brightness/color',
    screenshot_path     VARCHAR(256) COMMENT '截图文件相对路径',
    error_msg           TEXT COMMENT '错误信息',
    retry_used          INT DEFAULT 0 COMMENT '实际重试次数',
    register_to_ledger  TINYINT(1) DEFAULT 0 COMMENT '是否登记台账',
    capture_time        DATETIME COMMENT '截图时间',
    sync_version        BIGINT DEFAULT 0 COMMENT '⭐ 同步版本号',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inspect_id (inspect_id),
    INDEX idx_camera_code (camera_code),
    INDEX idx_sync_version (sync_version)
) COMMENT='摄像头巡检结果表';
```

#### 3.3.3 台账记录表

```sql
CREATE TABLE ledger_records (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    inspect_id          BIGINT NOT NULL COMMENT '关联巡检记录',
    camera_result_id    BIGINT COMMENT '关联摄像头结果',
    ledger_date         DATE NOT NULL COMMENT '台账日期',
    seq_no              INT COMMENT '序号',
    enterprise          VARCHAR(128) COMMENT '企业名称',
    camera_name         VARCHAR(128) COMMENT '摄像头名称',
    over_threshold      VARCHAR(20) COMMENT '是否超阈值',
    abnormal_note       TEXT COMMENT '其他异常情况',
    screenshot_file     VARCHAR(128) COMMENT '截图文件名',
    disposal_status     TEXT COMMENT '处置情况',
    docx_path           VARCHAR(256) COMMENT '生成的台账 docx 路径',
    sync_version        BIGINT DEFAULT 0 COMMENT '⭐ 同步版本号',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ledger_date (ledger_date),
    INDEX idx_inspect_id (inspect_id),
    INDEX idx_sync_version (sync_version)
) COMMENT='巡查台账记录表';
```

> **sync_version 说明**：环保小脑每次写入数据时，`sync_version` 取全局递增序列值（或用 `updated_at` 精确时间戳替代），鹊桥增量拉取时通过 `WHERE sync_version > {last_synced_version}` 过滤，只拉新增和变更数据，避免全量重复同步。

---

## 四、环保小脑定时任务与巡检流程

### 4.1 InspectionScheduler.java — 每小时执行

```java
@Component
@Slf4j
public class InspectionScheduler {

    @Autowired
    private InspectionService inspectionService;

    /**
     * 每小时整点自动执行巡检
     * cron: 秒 分 时 日 月 周
     * "0 0 * * * ?" = 每小时整点
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void hourlyInspection() {
        log.info("[InspectionScheduler] 定时巡检任务触发，时间：{}", LocalDateTime.now());
        try {
            inspectionService.executeInspection("auto");
        } catch (Exception e) {
            log.error("[InspectionScheduler] 巡检任务异常", e);
        }
    }
}
```

### 4.2 InspectionService.java — 核心巡检编排

```java
@Service
@Slf4j
@Transactional
public class InspectionService {

    @Autowired private CaptureService captureService;
    @Autowired private InspectionRecordMapper inspectionRecordMapper;
    @Autowired private CameraResultMapper cameraResultMapper;
    @Autowired private LedgerService ledgerService;
    @Autowired private FeishuNotifyService feishuNotifyService;
    @Autowired private CameraListReader cameraListReader;
    @Autowired private SyncVersionService syncVersionService;    // ⭐ 新增

    /**
     * 执行一次完整巡检
     * @param triggerType "auto" | "manual"
     */
    public String executeInspection(String triggerType) {
        LocalDateTime startTime = LocalDateTime.now();
        log.info("[Inspection] 开始执行巡检，触发类型：{}", triggerType);

        // ① 读取摄像头清单
        List<CameraInfo> cameraList = cameraListReader.readAll();
        log.info("[Inspection] 共读取 {} 路摄像头", cameraList.size());

        // ② 获取当前同步版本号
        long currentSyncVersion = syncVersionService.nextVersion();

        // ③ 创建巡检记录（running 状态）
        InspectionRecord record = new InspectionRecord();
        record.setInspectDate(LocalDate.now());
        record.setInspectTime(startTime);
        record.setTotalCount(cameraList.size());
        record.setTriggerType(triggerType);
        record.setStatus("running");
        record.setSyncVersion(currentSyncVersion);
        inspectionRecordMapper.insert(record);
        Long inspectId = record.getId();

        // ④ 逐路摄像头截图 + 质量判定
        List<CameraResult> results = new ArrayList<>();
        for (CameraInfo cam : cameraList) {
            try {
                CameraResult result = captureService.capture(cam, inspectId);
                result.setSyncVersion(currentSyncVersion);
                cameraResultMapper.insert(result);
                results.add(result);
            } catch (Exception e) {
                log.warn("[Inspection] 摄像头 {} 截图异常，跳过：{}", cam.getCameraName(), e.getMessage());
                CameraResult errorResult = buildErrorResult(cam, inspectId, e.getMessage());
                errorResult.setSyncVersion(currentSyncVersion);
                cameraResultMapper.insert(errorResult);
                results.add(errorResult);
            }
        }

        // ⑤ 汇总统计
        long onlineCount = results.stream().filter(r -> "online".equals(r.getStatus())).count();
        long offlineCount = results.stream().filter(r -> "offline".equals(r.getStatus())).count();
        long abnormalCount = results.stream().filter(r -> "abnormal".equals(r.getStatus())).count();

        // ⑥ 更新巡检记录为完成状态
        record.setOnlineCount((int) onlineCount);
        record.setOfflineCount((int) offlineCount);
        record.setAbnormalCount((int) abnormalCount);
        record.setStatus("completed");
        inspectionRecordMapper.updateById(record);

        // ⑦ 生成巡查台账 docx 并写 ledger_records
        List<CameraResult> ledgerTargets = results.stream()
                .filter(CameraResult::isRegisterToLedger)
                .collect(Collectors.toList());
        String docxPath = null;
        try {
            docxPath = ledgerService.generateAndSave(inspectId, ledgerTargets, currentSyncVersion);
            record.setLedgerCount(ledgerTargets.size());
            inspectionRecordMapper.updateById(record);
        } catch (Exception e) {
            log.error("[Inspection] 台账生成失败，不阻塞通知：{}", e.getMessage());
        }

        // ⑧ 推送飞书通知
        try {
            feishuNotifyService.sendInspectionReport(record, results, docxPath);
        } catch (Exception e) {
            log.error("[Inspection] 飞书通知失败：{}", e.getMessage());
        }

        log.info("[Inspection] 巡检完成：在线 {}，离线 {}，异常 {}", onlineCount, offlineCount, abnormalCount);
        return inspectId.toString();
    }
}
```

### 4.3 SyncVersionService.java — 同步版本号管理

```java
@Service
@Slf4j
public class SyncVersionService {

    @Autowired
    private SyncVersionMapper syncVersionMapper;

    /**
     * 获取下一个同步版本号（全局递增）
     * 简单实现：基于数据库序列或自增表
     */
    @Transactional
    public long nextVersion() {
        // 方案一：数据库自增序列
        return syncVersionMapper.nextVal();

        // 方案二（简化）：用当前时间戳毫秒数
        // return System.currentTimeMillis();
    }
}
```

```sql
-- 同步版本号序列表（方案一）
CREATE TABLE sync_version_seq (
    id BIGINT PRIMARY KEY AUTO_INCREMENT
) COMMENT='同步版本号序列';

-- 获取下一个值：INSERT INTO sync_version_seq VALUES (); SELECT LAST_INSERT_ID();
```

> **简化方案**：如果不想维护额外序列表，可用 `updated_at` 时间戳替代 `sync_version`，鹊桥增量拉取时用 `WHERE updated_at > '{last_synced_timestamp}'` 过滤。精度要求毫秒级，避免同一秒内多次写入混淆。

### 4.4 截图能力：CaptureService.java

与 v2.0 一致，Java 通过 ProcessBuilder 调用 Python 脚本：

```java
@Service
@Slf4j
public class CaptureService {

    @Value("${enviro.python.path:python3}")
    private String pythonPath;

    @Value("${enviro.scripts.capture-script:scripts/camera_capture.py}")
    private String captureScript;

    @Value("${enviro.config.path:config.json}")
    private String configPath;

    public CameraResult capture(CameraInfo cam, Long inspectId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            pythonPath,
            captureScript,
            "--config", configPath,
            "--code", cam.getCameraCode(),
            "--name", cam.getCameraName()
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("截图脚本执行失败: " + output);
        }

        return parseCaptureResult(output, cam, inspectId);
    }

    private CameraResult parseCaptureResult(String json, CameraInfo cam, Long inspectId) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        // ... 同 v2.0
    }
}
```

### 4.5 飞书通知：FeishuNotifyService.java

与 v2.0 一致，入库后直接推送飞书 Webhook：

```java
@Service
@Slf4j
public class FeishuNotifyService {

    @Value("${enviro.feishu.webhook-url}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public void sendInspectionReport(InspectionRecord record,
                                     List<CameraResult> results,
                                     String docxPath) {
        String cardJson = buildCardJson(record, results, docxPath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(cardJson, headers);

        try {
            restTemplate.postForObject(webhookUrl, entity, String.class);
            log.info("[FeishuNotify] 飞书通知发送成功");
        } catch (Exception e) {
            log.error("[FeishuNotify] 飞书通知发送失败：{}", e.getMessage());
        }
    }

    private String buildCardJson(InspectionRecord record,
                                  List<CameraResult> results,
                                  String docxPath) {
        // 飞书卡片消息格式 — 同 v2.0
        // ...
    }
}
```

---

## 五、环保小脑数据同步接口设计

### 5.1 接口总览（供鹊桥拉取，脑机端不直接调用）

| 接口 | 方法 | 路径 | 功能 |
|------|------|------|------|
| 增量同步巡检记录 | GET | `/api/v1/sync/inspections` | 拉取 `sync_version > since` 的巡检记录 |
| 增量同步摄像头结果 | GET | `/api/v1/sync/camera-results` | 拉取增量摄像头结果 |
| 增量同步台账记录 | GET | `/api/v1/sync/ledger-records` | 拉取增量台账记录 |
| 手动触发巡检 | POST | `/api/v1/inspections/trigger` | 鹊桥转发脑机端的手动触发请求 |
| 下载台账文件 | GET | `/api/v1/ledger/{id}/download` | 鹊桥转发脑机端的文件下载请求 |
| 查询同步水位 | GET | `/api/v1/sync/watermark` | 查询当前最大 `sync_version`（鹊桥判断是否有新数据） |
| 健康检查 | GET | `/actuator/health` | Spring Actuator 健康检查 |

### 5.2 增量同步接口详细设计

#### 5.2.1 增量同步巡检记录

```
GET /api/v1/sync/inspections

请求头：
  X-API-Key: {鹊桥专用 api_key}

查询参数：
  since        long     上次同步的 sync_version 水位，返回大于此值的所有记录
  limit        int      每次拉取上限，默认 100，最大 500

响应体（200 OK）：
{
  "code": 0,
  "message": "success",
  "data": {
    "watermark": 1709280001234,       // 本次返回数据的最大 sync_version
    "hasMore": false,                  // 是否还有更多数据
    "items": [
      {
        "id": 42,
        "inspectDate": "2026-07-02",
        "inspectTime": "2026-07-02T15:00:05",
        "totalCount": 25,
        "onlineCount": 22,
        "offlineCount": 1,
        "abnormalCount": 2,
        "ledgerCount": 18,
        "triggerType": "auto",
        "status": "completed",
        "syncVersion": 1709280001234,
        "createdAt": "2026-07-02T15:00:05",
        "updatedAt": "2026-07-02T15:12:30"
      }
    ]
  }
}
```

#### 5.2.2 增量同步摄像头结果

```
GET /api/v1/sync/camera-results

请求头：
  X-API-Key: {鹊桥专用 api_key}

查询参数：
  since        long     上次同步的 sync_version 水位
  inspect_id   long     可选：指定某次巡检的结果
  limit        int      每次拉取上限，默认 200

响应体（200 OK）：
{
  "code": 0,
  "data": {
    "watermark": 1709280001234,
    "hasMore": false,
    "items": [
      {
        "id": 501,
        "inspectId": 42,
        "cameraCode": "1eddcdf3018740bf9142b0c87bf5f9e3",
        "cameraName": "华达通危废仓库1",
        "enterprise": "惠州市华达通气体制造股份有限公司",
        "status": "online",
        "qualityScore": 0.82,
        "qualityDetail": "{\"laplacian\":128.5,\"brightness\":0.65}",
        "screenshotPath": "screenshots/20260702/华达通危废仓库1_20260702150123.jpg",
        "errorMsg": null,
        "retryUsed": 0,
        "registerToLedger": true,
        "captureTime": "2026-07-02T15:01:23",
        "syncVersion": 1709280001234,
        "createdAt": "2026-07-02T15:01:23"
      }
    ]
  }
}
```

#### 5.2.3 查询同步水位（快速判断是否有新数据）

```
GET /api/v1/sync/watermark

请求头：
  X-API-Key: {鹊桥专用 api_key}

响应体（200 OK）：
{
  "code": 0,
  "data": {
    "maxSyncVersion": 1709280001234,
    "lastInspectionTime": "2026-07-02T15:00:05",
    "totalInspectionCount": 42
  }
}
```

> **鹊桥同步策略**：鹊桥先调 `watermark` 判断是否有新数据（`maxSyncVersion > 鹊桥本地记录的 lastSyncVersion`），有则拉取增量数据，无则跳过本轮同步，节省网络开销。

#### 5.2.4 手动触发巡检（鹊桥转发）

```
POST /api/v1/inspections/trigger

请求头：
  X-API-Key: {鹊桥专用 api_key}
  Content-Type: application/json

请求体：
{
  "reason": "脑机端用户手动触发"
}

响应体（202 Accepted）：
{
  "code": 0,
  "message": "巡检任务已提交",
  "data": {
    "taskId": 42,
    "status": "running",
    "estimatedDuration": "约 5-10 分钟"
  }
}
```

> **说明**：手动触发是操作类请求，鹊桥需要转发到环保小脑执行。触发后环保小脑入库 + 推飞书，下一轮鹊桥同步时数据自动到达鹊桥数据库。

### 5.3 SyncController.java 实现

```java
@RestController
@RequestMapping("/api/v1/sync")
@Slf4j
public class SyncController {

    @Autowired private InspectionRecordMapper inspectionRecordMapper;
    @Autowired private CameraResultMapper cameraResultMapper;
    @Autowired private LedgerRecordMapper ledgerRecordMapper;

    /**
     * 增量同步巡检记录
     */
    @GetMapping("/inspections")
    public ResponseEntity<ApiResponse<SyncResponse<InspectionRecord>>> syncInspections(
            @RequestParam long since,
            @RequestParam(defaultValue = "100") int limit) {

        List<InspectionRecord> items = inspectionRecordMapper.selectBySyncVersion(since, limit);
        long watermark = items.stream()
                .mapToLong(InspectionRecord::getSyncVersion)
                .max().orElse(since);
        boolean hasMore = items.size() >= limit;

        return ResponseEntity.ok(ApiResponse.success(
            new SyncResponse<>(watermark, hasMore, items)));
    }

    /**
     * 增量同步摄像头结果
     */
    @GetMapping("/camera-results")
    public ResponseEntity<ApiResponse<SyncResponse<CameraResult>>> syncCameraResults(
            @RequestParam long since,
            @RequestParam(defaultValue = "200") int limit) {

        List<CameraResult> items = cameraResultMapper.selectBySyncVersion(since, limit);
        long watermark = items.stream()
                .mapToLong(CameraResult::getSyncVersion)
                .max().orElse(since);
        boolean hasMore = items.size() >= limit;

        return ResponseEntity.ok(ApiResponse.success(
            new SyncResponse<>(watermark, hasMore, items)));
    }

    /**
     * 增量同步台账记录
     */
    @GetMapping("/ledger-records")
    public ResponseEntity<ApiResponse<SyncResponse<LedgerRecord>>> syncLedgerRecords(
            @RequestParam long since,
            @RequestParam(defaultValue = "200") int limit) {

        List<LedgerRecord> items = ledgerRecordMapper.selectBySyncVersion(since, limit);
        long watermark = items.stream()
                .mapToLong(LedgerRecord::getSyncVersion)
                .max().orElse(since);
        boolean hasMore = items.size() >= limit;

        return ResponseEntity.ok(ApiResponse.success(
            new SyncResponse<>(watermark, hasMore, items)));
    }

    /**
     * 查询当前同步水位
     */
    @GetMapping("/watermark")
    public ResponseEntity<ApiResponse<WatermarkResponse>> getWatermark() {
        long maxVersion = inspectionRecordMapper.selectMaxSyncVersion();
        LocalDateTime lastTime = inspectionRecordMapper.selectLastInspectTime();
        long totalCount = inspectionRecordMapper.selectCount();

        return ResponseEntity.ok(ApiResponse.success(
            new WatermarkResponse(maxVersion, lastTime, totalCount)));
    }
}
```

---

## 六、鹊桥平台数据同步层设计

### 6.1 鹊桥数据同步架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                          鹊桥平台                                     │
│                                                                        │
│   ┌─────────────────────────────────────────────────────────────┐    │
│   │  SyncScheduler（鹊桥定时同步）                                │    │
│   │  每 30 分钟执行一次：                                         │    │
│   │                                                              │    │
│   │  ① GET /api/v1/sync/watermark → 判断是否有新数据             │    │
│   │  ② 有新数据 → 依次拉取三张表增量数据                          │    │
│   │  ③ 写入鹊桥自有数据库（MySQL / PostgreSQL）                   │    │
│   │  ④ 更新鹊桥本地 lastSyncVersion 水位                         │    │
│   │  ⑤ 同步失败 → 记录日志，不影响已有数据服务                    │    │
│   └─────────────────────────────────────────────────────────────┘    │
│                                                                        │
│   ┌─────────────────────────────────────────────────────────────┐    │
│   │  鹊桥自有数据库                                               │    │
│   │  synced_inspection_records    synced_camera_results           │    │
│   │  synced_ledger_records        sync_watermark（水位记录）       │    │
│   └─────────────────────────────────────────────────────────────┘    │
│                                                                        │
│   ┌─────────────────────────────────────────────────────────────┐    │
│   │  MCP 工具层                                                   │    │
│   │  查询类 → 鹊桥自有数据库直接响应                               │    │
│   │  操作类 → 转发环保小脑 API                                    │    │
│   └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

### 6.2 鹊桥同步流程详细

```
鹊桥 SyncScheduler（每 30 分钟）

① 调环保小脑 GET /api/v1/sync/watermark
   → maxSyncVersion = 1709280001234
   → 鹊桥本地 lastSyncVersion = 1709270000000
   → 1709280001234 > 1709270000000 → 有新数据

② 调 GET /api/v1/sync/inspections?since=1709270000000&limit=100
   → 拉取增量巡检记录 → 写入 synced_inspection_records
   → 更新本地水位 watermark = 返回的 watermark 值

③ 调 GET /api/v1/sync/camera-results?since=1709270000000&limit=200
   → 拉取增量摄像头结果 → 写入 synced_camera_results
   → 更新本地水位

④ 调 GET /api/v1/sync/ledger-records?since=1709270000000&limit=200
   → 拉取增量台账记录 → 写入 synced_ledger_records
   → 更新本地水位

⑤ 更新 sync_watermark 表：lastSyncVersion = max(各表返回的 watermark)
   → 下次同步从新的水位开始

⑥ 如果环保小脑不可达（网络故障）：
   → 记录日志，不更新水位
   → 不影响已有数据的 MCP 查询服务（返回最近一次成功同步的数据）
```

### 6.3 鹊桥自有数据库设计

#### synced_inspection_records（同步后的巡检记录）

```sql
CREATE TABLE synced_inspection_records (
    id                  BIGINT PRIMARY KEY COMMENT '环保小脑原始 ID',
    inspect_date        DATE NOT NULL,
    inspect_time        DATETIME NOT NULL,
    total_count         INT,
    online_count        INT,
    offline_count       INT,
    abnormal_count      INT,
    ledger_count        INT,
    trigger_type        VARCHAR(20) DEFAULT 'auto',
    status              VARCHAR(20) DEFAULT 'completed',
    sync_version        BIGINT NOT NULL,
    synced_at           DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '鹊桥同步时间',
    INDEX idx_inspect_date (inspect_date),
    INDEX idx_sync_version (sync_version)
) COMMENT='鹊桥同步的巡检记录';
```

#### synced_camera_results（同步后的摄像头结果）

```sql
CREATE TABLE synced_camera_results (
    id                  BIGINT PRIMARY KEY COMMENT '环保小脑原始 ID',
    inspect_id          BIGINT NOT NULL,
    camera_code         VARCHAR(64) NOT NULL,
    camera_name         VARCHAR(128),
    enterprise          VARCHAR(128),
    status              VARCHAR(20),
    quality_score       DECIMAL(4,2),
    quality_detail      TEXT,
    screenshot_path     VARCHAR(256),
    error_msg           TEXT,
    retry_used          INT DEFAULT 0,
    register_to_ledger  TINYINT(1) DEFAULT 0,
    capture_time        DATETIME,
    sync_version        BIGINT NOT NULL,
    synced_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inspect_id (inspect_id),
    INDEX idx_camera_code (camera_code),
    INDEX idx_status (status)
) COMMENT='鹊桥同步的摄像头结果';
```

#### synced_ledger_records（同步后的台账记录）

```sql
CREATE TABLE synced_ledger_records (
    id                  BIGINT PRIMARY KEY COMMENT '环保小脑原始 ID',
    inspect_id          BIGINT NOT NULL,
    camera_result_id    BIGINT,
    ledger_date         DATE NOT NULL,
    seq_no              INT,
    enterprise          VARCHAR(128),
    camera_name         VARCHAR(128),
    over_threshold      VARCHAR(20),
    abnormal_note       TEXT,
    screenshot_file     VARCHAR(128),
    disposal_status     TEXT,
    docx_path           VARCHAR(256),
    sync_version        BIGINT NOT NULL,
    synced_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ledger_date (ledger_date),
    INDEX idx_inspect_id (inspect_id)
) COMMENT='鹊桥同步的台账记录';
```

#### sync_watermark（同步水位记录）

```sql
CREATE TABLE sync_watermark (
    id                  INT PRIMARY KEY AUTO_INCREMENT,
    table_name          VARCHAR(64) NOT NULL COMMENT '同步的表名',
    last_sync_version   BIGINT NOT NULL COMMENT '上次同步到的版本号',
    last_sync_time      DATETIME NOT NULL COMMENT '上次同步时间',
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_table_name (table_name)
) COMMENT='鹊桥同步水位记录';
```

### 6.4 事件驱动同步（可选增强）

除了定时拉取，环保小脑可在入库完成后**主动回调鹊桥**，通知有新数据可同步：

```
环保小脑入库完成
    └──→ 回调鹊桥 POST /api/notify/new-data
          Body: {"syncVersion": 1709280001234, "type": "inspection_completed"}
          └──→ 鹊桥立即触发一轮增量同步
```

**优点**：数据到达鹊桥的延迟从 30min 降低到秒级。
**缺点**：增加环保小脑对鹊桥的网络依赖，回调失败不影响数据入库主流程（只记录日志）。

> 建议实现方式：环保小脑 `FeishuNotifyService` 之后，新增一个 `QueqiaoNotifyService`，同样用 RestTemplate POST 回调鹊桥通知接口。回调失败不抛异常。

---

## 七、鹊桥 MCP 工具设计

### 7.1 查询类工具（鹊桥自有数据库直接响应）

查询类工具**不穿透环保小脑**，直接从鹊桥自有数据库查询：

#### 工具一：get_inspection_ledger

```json
{
  "name": "get_inspection_ledger",
  "description": "获取环保危废仓库摄像头巡查台账。可按日期、企业名称、摄像头状态筛选，返回结构化台账数据。",
  "parameters": {
    "type": "object",
    "properties": {
      "date": {
        "type": "string",
        "description": "查询日期 YYYY-MM-DD，不填则查今日台账。"
      },
      "enterprise": {
        "type": "string",
        "description": "企业名称关键词，支持模糊匹配。"
      },
      "status": {
        "type": "string",
        "enum": ["online", "offline", "abnormal"],
        "description": "摄像头状态过滤"
      }
    }
  },
  "dataSource": "鹊桥自有数据库 synced_ledger_records + synced_camera_results",
  "note": "数据来源为鹊桥从环保小脑同步的数据，可能存在最多 30 分钟延迟"
}
```

#### 工具二：get_camera_status

```json
{
  "name": "get_camera_status",
  "description": "查询摄像头最新状态快照或某路摄像头历史状态变化。",
  "parameters": {
    "type": "object",
    "properties": {
      "camera_name": {
        "type": "string",
        "description": "摄像头名称关键词"
      },
      "enterprise": {
        "type": "string",
        "description": "所属企业名称"
      },
      "history_days": {
        "type": "integer",
        "default": 7,
        "description": "返回最近 N 天历史状态"
      }
    }
  },
  "dataSource": "鹊桥自有数据库 synced_camera_results"
}
```

#### 工具三：get_inspection_summary

```json
{
  "name": "get_inspection_summary",
  "description": "获取指定时间范围内的摄像头巡检统计摘要，包括总体在线率、最差记录日、频繁离线摄像头排名。",
  "parameters": {
    "type": "object",
    "properties": {
      "start_date": {
        "type": "string",
        "description": "统计开始日期 YYYY-MM-DD"
      },
      "end_date": {
        "type": "string",
        "description": "统计结束日期 YYYY-MM-DD"
      }
    }
  },
  "dataSource": "鹊桥自有数据库 synced_inspection_records + synced_camera_results"
}
```

### 7.2 操作类工具（鹊桥转发环保小脑）

#### 工具四：trigger_inspection

```json
{
  "name": "trigger_inspection",
  "description": "手动触发一次摄像头巡检任务。环保小脑每小时自动执行，如需立即巡检可调用此工具。返回任务 ID，约 10 分钟后数据同步到鹊桥可查询。",
  "parameters": {
    "type": "object",
    "properties": {
      "reason": {
        "type": "string",
        "description": "触发原因说明，可选"
      }
    }
  },
  "forwardTo": "环保小脑 POST /api/v1/inspections/trigger",
  "forwardHeaders": { "X-API-Key": "${ENVIRO_API_KEY}" },
  "note": "转发到环保小脑执行，结果通过数据同步自动到达鹊桥"
}
```

#### 工具五：download_ledger_docx

```json
{
  "name": "download_ledger_docx",
  "description": "下载指定巡检记录的巡查台账 Word 文档（.docx 格式）。",
  "parameters": {
    "type": "object",
    "required": ["inspect_id"],
    "properties": {
      "inspect_id": {
        "type": "integer",
        "description": "巡检记录 ID（可先调用 get_inspection_ledger 获取）"
      }
    }
  },
  "forwardTo": "环保小脑 GET /api/v1/ledger/{inspect_id}/download",
  "forwardHeaders": { "X-API-Key": "${ENVIRO_API_KEY}" },
  "note": "转发到环保小脑下载文件，因为 docx 文件存储在环保小脑服务器上"
}
```

> **工具分类原则**：
> - **查询类**（ledger/camera/summary）：数据在鹊桥自有数据库，直接响应，速度快，环保小脑宕机时仍可返回历史数据
> - **操作类**（trigger/download）：必须穿透环保小脑，因为执行逻辑和文件存储在环保小脑侧

---

## 八、数据流向完整说明

### 8.1 写路径：环保小脑每小时巡检（自治）

```
Spring @Scheduled（每小时整点）
    │
    ▼
InspectionScheduler.hourlyInspection()
    │
    ▼
InspectionService.executeInspection("auto")
    │
    ├── ① 读摄像头清单（Excel / DB）
    │
    ├── ② 获取 syncVersion（全局递增）
    │
    ├── ③ 创建 inspection_records（status=running, syncVersion=xxx）
    │
    ├── ④ 逐路 CaptureService（ProcessBuilder 调 Python）
    │      └─ 写入 camera_results（syncVersion=xxx）
    │
    ├── ⑤ 汇总统计 → 更新 inspection_records（status=completed）
    │
    ├── ⑥ LedgerService.generateAndSave()
    │      ├─ Apache POI 生成台账 docx
    │      └─ 写入 ledger_records（syncVersion=xxx）
    │
    ├── ⑦ FeishuNotifyService.sendInspectionReport()
    │      └─ 推送飞书 Webhook 卡片
    │
    └── ⑧（可选）QueqiaoNotifyService.notifyNewData()
           └─ 回调鹊桥通知有新数据可同步
```

### 8.2 同步路径：鹊桥定时从环保小脑拉取数据

```
鹊桥 SyncScheduler（每 30 分钟）
    │
    ├── ① GET /api/v1/sync/watermark → 判断是否有新数据
    │      └─ maxSyncVersion > 鹊桥 lastSyncVersion → 有新数据
    │
    ├── ② GET /api/v1/sync/inspections?since={lastSyncVersion}&limit=100
    │      └─ 写入 synced_inspection_records
    │
    ├── ③ GET /api/v1/sync/camera-results?since={lastSyncVersion}&limit=200
    │      └─ 写入 synced_camera_results
    │
    ├── ④ GET /api/v1/sync/ledger-records?since={lastSyncVersion}&limit=200
    │      └─ 写入 synced_ledger_records
    │
    └── ⑤ 更新 sync_watermark 表各表的 lastSyncVersion
```

### 8.3 读路径：脑机端查询台账（鹊桥直接响应）

```
脑机桌面端：「帮我查今天宙邦公司的摄像头巡查情况」
    │
    ▼ 鹊桥 MCP 意图识别
    │
    ▼ MCP 工具调用：get_inspection_ledger(enterprise="宙邦", date="2026-07-02")
    │
    ▼ 鹊桥查询自有数据库（synced_camera_results + synced_ledger_records）
    │   ⚠ 不穿透环保小脑，直接查鹊桥 MySQL
    │
    ▼ 返回 JSON（台账数据）
    │
    ▼ 鹊桥格式化为自然语言
    │
    ▼ 脑机桌面端展示：
      「今天（2026-07-02）宙邦公司共 3 路摄像头：
        ✅ 宙邦-177 危废仓4：正常
        ✅ 宙邦-178 危废仓5：正常
        ⚠️ 宙邦-176 危废仓3：异常（画面质量评分 0.31）」
```

### 8.4 操作路径：手动触发（鹊桥转发环保小脑）

```
脑机桌面端：「帮我跑一次巡检」
    │
    ▼ 鹊桥 MCP：trigger_inspection(reason="用户手动触发")
    │
    ▼ 鹊桥转发 → 环保小脑 POST /api/v1/inspections/trigger
    │
    ▼ 环保小脑执行 → 入库 → 推飞书
    │
    ▼ 下一轮鹊桥同步 → 数据到达鹊桥数据库
    │
    ▼ 脑机端稍后查询 → 鹊桥自有数据库响应
```

---

## 九、鹊桥 MCP 内部实现参考

### 9.1 鹊桥 MCP Service 层（查询类）

```java
@Service
@Slf4j
public class EnviroInspectionMcpService {

    @Autowired private SyncedInspectionRecordMapper syncedInspectionMapper;
    @Autowired private SyncedCameraResultMapper syncedCameraMapper;
    @Autowired private SyncedLedgerRecordMapper syncedLedgerMapper;

    /**
     * 获取巡查台账（从鹊桥自有数据库）
     */
    public LedgerListResponse getInspectionLedger(String date, String enterprise,
                                                    String status, int page, int pageSize) {
        String targetDate = (date != null) ? date : LocalDate.now().toString();

        // 查询同步的巡检记录
        SyncedInspectionRecord inspection = syncedInspectionMapper
                .selectByInspectDate(targetDate);

        if (inspection == null) {
            return LedgerListResponse.empty(targetDate);
        }

        // 查询同步的摄像头结果
        List<SyncedCameraResult> results = syncedCameraMapper
                .selectByInspectId(inspection.getId(), enterprise, status);

        // 查询同步的台账记录
        List<SyncedLedgerRecord> ledgerRecords = syncedLedgerMapper
                .selectByInspectId(inspection.getId());

        // 构建响应
        return LedgerListResponse.build(inspection, results, ledgerRecords, page, pageSize);
    }

    /**
     * 获取摄像头状态（从鹊桥自有数据库）
     */
    public CameraStatusResponse getCameraStatus(String cameraName,
                                                  String enterprise,
                                                  int historyDays) {
        // ... 从 synced_camera_results 查询
    }

    /**
     * 获取统计摘要（从鹊桥自有数据库）
     */
    public InspectionSummaryResponse getInspectionSummary(String startDate,
                                                           String endDate) {
        // ... 从 synced_inspection_records + synced_camera_results 聚合
    }
}
```

### 9.2 鹊桥 MCP Service 层（操作类 — 转发环保小脑）

```java
@Service
@Slf4j
public class EnviroInspectionForwardService {

    @Value("${enviro-brain.base-url}")
    private String enviroBrainBaseUrl;

    @Value("${enviro-brain.api-key}")
    private String enviroBrainApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 手动触发巡检（转发环保小脑）
     */
    public TriggerResponse triggerInspection(String reason) {
        String url = enviroBrainBaseUrl + "/api/v1/inspections/trigger";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", enviroBrainApiKey);

        HttpEntity<TriggerRequest> entity = new HttpEntity<>(new TriggerRequest(reason), headers);

        try {
            ResponseEntity<ApiResponse> response = restTemplate.postForEntity(url, entity, ApiResponse.class);
            return parseTriggerResponse(response.getBody());
        } catch (Exception e) {
            log.error("[ForwardService] 转发触发请求失败：{}", e.getMessage());
            throw new RuntimeException("环保小脑服务不可达，请稍后重试");
        }
    }

    /**
     * 下载台账文件（转发环保小脑）
     */
    public Resource downloadLedgerDocx(Long inspectId) {
        String url = enviroBrainBaseUrl + "/api/v1/ledger/" + inspectId + "/download";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", enviroBrainApiKey);

        try {
            return restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), Resource.class).getBody();
        } catch (Exception e) {
            log.error("[ForwardService] 转发下载请求失败：{}", e.getMessage());
            throw new RuntimeException("台账文件下载失败");
        }
    }
}
```

---

## 十、部署架构

### 10.1 部署示意

```
┌────────────────────────────────────────────────────────────────────────────┐
│                              内网环境                                        │
│                                                                              │
│  ┌─────────────────┐          ┌────────────────────────────────────────┐   │
│  │  脑机桌面端       │◀────────▶│          鹊桥平台服务器                    │   │
│  │                  │  HTTPS   │                                          │   │
│  │                  │          │  MCP Server: enviro-inspection           │   │
│  │                  │          │  数据同步：每 30min 拉取增量               │   │
│  │                  │          │  自有数据库：synced_*                     │   │
│  │                  │          │  操作转发：环保小脑 API                    │   │
│  └─────────────────┘          └───────────────┬────────────────────────┘   │
│                                                │                            │
│                 ⚠ 脑机桌面端不直接访问此服务     │ 数据同步 + 操作转发         │
│                                                │ HTTP + X-API-Key           │
│                                                ▼                            │
│                                ┌────────────────────────────────────────┐   │
│                                │    环保小脑 Java 服务（Spring Boot）       │   │
│                                │    端口：8080                              │   │
│                                │    @Scheduled 每小时整点                   │   │
│                                │    MySQL 数据库（三张表 + sync_version）   │   │
│                                └───────────┬────────────────────────────┘   │
│                                            │                                 │
│                       ┌────────────────────┼──────────────────────┐         │
│                       ▼                    ▼                      ▼         │
│                摄像头 RTSP 流        海康 Artemis API      飞书 Webhook      │
└────────────────────────────────────────────────────────────────────────────┘
```

### 10.2 环保小脑启动与部署

```bash
# Maven 打包
mvn clean package -DskipTests

# 启动 Spring Boot 服务
java -jar target/enviro-brain-1.0.0.jar \
  --spring.config.location=application.yml \
  --server.port=8080

# systemd 守护进程
[Unit]
Description=Enviro Brain Spring Boot Service
After=mysql.service

[Service]
Type=simple
User=enviro
WorkingDirectory=/opt/enviro-brain
ExecStart=/usr/bin/java -jar enviro-brain-1.0.0.jar
Restart=always

[Install]
WantedBy=multi-user.target
```

### 10.3 application.yml 配置

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/enviro_brain?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: enviro
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver
  scheduling:
    enabled: true

mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.enviro.brain.entity
  configuration:
    map-underscore-to-camel-case: true

enviro:
  # API 认证（鹊桥专用 key）
  api:
    valid-keys:
      - "qbridge-sync-key-xxx"      # 鹊桥同步专用 key
      - "qbridge-forward-key-xxx"   # 鹊桥转发操作专用 key
      - "admin-your-admin-key"      # 管理用 key

  # 飞书通知
  feishu:
    webhook-url: "https://open.feishu.cn/open-apis/bot/v2/hook/YOUR_ID"

  # ⭐ 鹊桥回调通知（可选）
  queqiao:
    notify-url: "https://<鹊桥平台>/api/notify/new-data"
    enabled: true

  # Python 截图脚本
  python:
    path: "python3"
  scripts:
    capture-script: "scripts/camera_capture.py"
  config:
    path: "config.json"

  # 文件路径
  paths:
    screenshot-root: "screenshots"
    ledger-output: "台账"
    docx-template: "src/main/resources/templates/危废仓库巡查台账_新模版.docx"

  # 摄像头清单
  camera-list:
    source: excel
    excel-path: "过程材料/环保摄像头巡查清单.xlsx"

management:
  endpoints:
    web:
      exposure:
        include: health, info
```

---

## 十一、脑机桌面端集成指南

### 11.1 MCP 接入配置

```json
{
  "mcpServers": {
    "enviro-inspection": {
      "url": "https://<鹊桥平台MCP网关地址>/mcp/enviro-inspection",
      "headers": {
        "Authorization": "Bearer <脑机端用户令牌>"
      }
    }
  }
}
```

> **⚠ 注意**：脑机桌面端只配置鹊桥 MCP 地址，**不配置环保小脑地址**。所有请求通过鹊桥中转。

### 11.2 典型对话示例

**场景 1：查询今日台账**
```
用户：「今天危废仓库的巡查情况怎么样？」
↓ MCP：get_inspection_ledger(date=today)
↓ 鹊桥查询自有数据库 → 返回
回复：「今天（2026-07-02）共巡检 25 路摄像头：
  ✅ 在线正常：22 路
  ❌ 离线：1 路
  ⚠️ 画面异常：2 路
  📋 数据更新时间：约 20 分钟前同步」
```

**场景 2：统计分析**
```
用户：「最近一周摄像头整体情况怎么样？」
↓ MCP：get_inspection_summary(start_date=7天前, end_date=today)
↓ 鹊桥聚合自有数据库 → 返回
回复：「近 7 天巡检统计：
  平均在线率：91.2%
  最差记录：2026-06-28（离线 3 路）
  频繁离线：宙邦-176 危废仓3（本周 3 次）」
```

**场景 3：手动触发**
```
用户：「现在帮我跑一次巡检」
↓ MCP：trigger_inspection(reason="用户手动触发")
↓ 鹊桥转发环保小脑 → 执行入库 → 推飞书
↓ 下一轮鹊桥同步后可查询结果
回复：「✅ 巡检任务已启动，预计 10 分钟完成。
  数据同步到鹊桥约需 30 分钟，完成后可查询结果。」
```

**场景 4：下载台账**
```
用户：「把今天的台账 Word 文档发给我」
↓ MCP：get_inspection_ledger(date=today) → 取 inspectId
↓ MCP：download_ledger_docx(inspect_id=xxx)
↓ 鹊桥转发环保小脑下载文件
回复：「[📄 危废仓库巡查台账_20260702.docx]（点击下载）」
```

---

## 十二、容错与异常处理

### 12.1 鹊桥容错策略

| 场景 | 处理方式 |
|------|----------|
| 环保小脑宕机，同步失败 | 鹊桥不更新水位，记录日志；**仍可从自有数据库返回最近一次成功同步的历史数据** |
| 环保小脑恢复后 | 鹊桥下一轮同步自动拉取所有增量数据，水位补齐 |
| 同步数据延迟（30min 内） | MCP 查询时在响应中附注 `数据更新时间`，让用户知道数据时效 |
| 手动触发转发失败 | 返回提示「环保小脑服务暂时不可用，请稍后重试」 |
| 文件下载转发失败 | 返回提示「台账文件暂时无法下载，请稍后重试」 |
| 数据重复同步（幂等） | 鹊桥写入时用环保小脑原始 ID 做 PRIMARY KEY，INSERT ON DUPLICATE KEY UPDATE |

### 12.2 环保小脑容错策略

| 场景 | 处理方式 |
|------|----------|
| 单路摄像头截图失败 | 单个失败不中断整体，记录 error_msg，继续下一路 |
| 飞书 Webhook 推送失败 | 不抛异常，记录日志，不影响入库主流程 |
| 台账 docx 生成失败 | try-catch 隔离，不影响飞书通知 |
| 鹊桥回调通知失败 | 不抛异常，记录日志，不影响入库主流程（鹊桥下次定时同步时自然会拉到数据） |
| 数据库写入失败 | HikariCP 连接池 + 重试机制；严重时写本地文件兜底 |

### 12.3 数据时效性说明

| 数据类型 | 环保小脑入库时效 | 鹊桥同步时效 | 脑机端可查时效 |
|---------|---------------|-------------|--------------|
| 定时巡检（每小时） | 小时级（整点执行） | ≤ 30min（定时同步） | ≤ 1.5h（最差） |
| 手动触发 | 约 5-10min | ≤ 30min | ≤ 40min（最差） |
| 事件驱动同步（可选） | 小时级 | ≤ 10s（回调触发） | ≤ 10min |

> **事件驱动同步可大幅缩短延迟**：环保小脑入库后立即回调鹊桥 → 鹊桥立刻拉取增量 → 脑机端查询时数据已就绪。

---

## 十三、风险评估

| 风险 | 等级 | 对策 |
|------|------|------|
| 每小时执行对摄像头/海康 API 压力增大 | 🟡 中 | 环保小脑串行截图，约 25路 × 20s = ~8min，每小时整点执行，不重叠；海康 API 限流需确认 |
| 鹊桥同步延迟导致数据时效差 | 🟡 中 | 事件驱动回调通知可大幅缩短延迟；MCP 响应附注数据更新时间 |
| 环保小脑宕机时鹊桥返回过期数据 | 🟡 中 | 鹊桥容错设计：返回最近一次成功同步的数据 + 附注时效说明 |
| 鹊桥与环保小脑数据不一致 | 🟢 低 | 增量同步 + 幂等写入 + ON DUPLICATE KEY UPDATE |
| Java 调 Python 进程开销 | 🟡 中 | 同 v2.0，25路串行约 8min，每小时执行可接受 |
| 鹊桥数据库与环保小脑数据库双写运维 | 🟡 中 | 鹊桥表结构镜像环保小脑，运维成本低；增量同步机制保证数据一致 |
| API Key 泄露 | 🟡 中 | 环保小脑只暴露给鹊桥（内网）；鹊桥对脑机端用 OAuth 令牌 |

---

## 十四、实施路线图

### 14.1 分阶段实施

```
Phase 1（环保小脑基础，约 3 天）
  ├─ 创建 Spring Boot 项目，引入依赖
  ├─ 设计并初始化 MySQL 数据库（三张表 + sync_version_seq）
  ├─ 实现 API Key 拦截器 + /actuator/health
  ├─ 读取摄像头清单（Excel/DB）
  └─ 实现数据同步接口（SyncController：watermark + 三个增量接口）

Phase 2（环保小脑巡检核心，约 3-4 天）
  ├─ 改造 Python camera_capture.py（新增 CLI 参数 + JSON 输出）
  ├─ 实现 CaptureService（ProcessBuilder 调用 Python）
  ├─ 实现 InspectionService 完整流程（每小时执行）
  ├─ 实现 SyncVersionService（全局递增版本号）
  ├─ 实现 FeishuNotifyService（飞书 Webhook）
  ├─ 实现 QueqiaoNotifyService（可选：回调鹊桥通知）
  └─ 实现 LedgerService + DocxGeneratorService（Apache POI）

Phase 3（鹊桥数据同步层，约 2-3 天）
  ├─ 鹊桥新建 synced_* 数据库表 + sync_watermark
  ├─ 实现 SyncScheduler（每 30min 拉取增量数据）
  ├─ 实现幂等写入逻辑（INSERT ON DUPLICATE KEY UPDATE）
  ├─ 鹊桥可选：接收环保小脑回调通知接口
  └─ 联调验证：环保小脑入库 → 鹊桥同步 → 数据一致

Phase 4（鹊桥 MCP 封装，约 2 天）
  ├─ 鹊桥 MCP 查询类工具（自有数据库直接响应）
  ├─ 鹊桥 MCP 操作类工具（转发环保小脑）
  ├─ 脑机桌面端接入测试
  └─ 典型对话场景验证

Phase 5（验收上线，约 1 天）
  ├─ 生产环境部署
  ├─ 端到端联调（每小时巡检 → 同步 → MCP → 自然语言查询）
  ├─ 容错测试（环保小脑宕机 → 鹊桥仍可返回历史数据）
  └─ 操作文档交付
```

### 14.2 验收标准

| 验收点 | 验证方式 |
|-------|----------|
| 每小时定时巡检 | 每个整点自动执行，inspection_records 有新记录，飞书收到通知 |
| 数据入库 | camera_results + ledger_records 有逐路/逐条记录 |
| 鹊桥数据同步 | 鹊桥 synced_* 表数据与环保小脑一致，水位递增 |
| 鹊桥查询类 MCP | get_inspection_ledger 从鹊桥自有数据库返回，不穿透环保小脑 |
| 鹊桥操作类转发 | trigger_inspection 转发环保小脑执行，结果通过同步到达鹊桥 |
| 脑机端自然语言查询 | 自然语言问题正确返回台账数据 |
| 容错验证 | 环保小脑停止后，鹊桥仍可返回最近同步的历史数据 |
| 增量同步效率 | 同步只拉增量数据（since > lastSyncVersion），不全量重复 |
| 手动触发 | 转发环保小脑后约 10min 完成，下一轮同步后鹊桥可查 |

### 14.3 实施前确认清单

| # | 确认项 | 状态 |
|---|--------|------|
| 1 | Java 运行环境（JDK 17+）已就绪 | ☐ |
| 2 | 环保小脑 MySQL 数据库已部署（三张表 + sync_version_seq） | ☐ |
| 3 | 鹊桥自有数据库已部署（synced_* + sync_watermark） | ☐ |
| 4 | Python 环境 + opencv/requests 已安装 | ☐ |
| 5 | 海康 Artemis API 凭证已就绪 | ☐ |
| 6 | 飞书机器人 Webhook URL 已创建 | ☐ |
| 7 | 摄像头清单 Excel 路径已确认 | ☐ |
| 8 | 台账 docx 模板已放入 resources/templates | ☐ |
| 9 | 环保小脑 → 鹊桥网络连通（内网） | ☐ |
| 10 | 鹊桥 → 环保小脑 API Key 配置完成 | ☐ |
| 11 | 鹊桥平台 MCP Server 配置就绪 | ☐ |
| 12 | 鹊桥定时同步调度（每 30min）配置完成 | ☐ |
| 13 | 环保小脑服务器可访问海康平台（443）和 RTSP 流（554） | ☐ |

---

## 十五、与原 v2.0 方案的差异总结

| # | 变化点 | v2.0 | v3.0 |
|---|--------|------|------|
| 1 | 执行频率 | 每日 15:00 | **每小时整点** |
| 2 | 鹊桥角色 | HTTP 透传代理 | **独立数据代理层**（自有存储+自主响应） |
| 3 | 脑机端数据来源 | 直接穿透环保小脑 | **仅从鹊桥获取** |
| 4 | 环保小脑 API 类型 | 通用查询 API | **增量同步 API** |
| 5 | 数据库 | 仅环保小脑 MySQL | 环保小脑 MySQL **+ 鹊桥自有数据库** |
| 6 | 容错能力 | 环保小脑宕机 = 服务不可用 | 环保小脑宕机 → 鹊桥返回最近历史数据 |
| 7 | 数据时效 | 实时（穿透查询） | ≤ 30min 延迟（定时同步） |
| 8 | 新增字段 | 无 | `sync_version`（增量同步水位） |
| 9 | 新增模块 | 无 | SyncController（环保小脑侧）、SyncScheduler（鹊桥侧）、QueqiaoNotifyService（可选回调） |

---

_文档版本：v3.0 | 创建日期：2026-07-02 | 核心变化：鹊桥从透传代理升级为数据同步层，环保小脑每小时执行，脑机端与环保小脑完全解耦，新增增量同步机制_
