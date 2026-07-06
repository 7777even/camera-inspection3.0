## Context

环保危废仓库摄像头自动巡检系统 v3.0 采用三层架构。本项目从零开始，Phase 1 聚焦环保小脑层的基础设施搭建。当前环境：Windows 开发机，Java 17 + Maven 管理，MySQL 8.x 本地实例。

## Goals / Non-Goals

**Goals:**
- 搭建可编译运行的 Spring Boot 3.x 项目骨架
- 创建 MySQL 4 张表并验证 MyBatis Mapper 可用
- 实现 sync_version 全局递增序列，作为增量同步的水位标记
- 实现 API Key 认证拦截，所有业务 API 受保护
- 提供同步接口供鹊桥拉取增量数据（Phase 3 使用）

**Non-Goals:**
- 不实现定时巡检调度逻辑（Phase 2）
- 不实现 Python 截图脚本调用（Phase 2）
- 不实现飞书通知推送（Phase 2）
- 不实现台账 docx 生成（Phase 2）
- 不实现鹊桥侧的同步调度（Phase 3）
- 不涉及 MCP 协议封装（Phase 4）

## Decisions

### D1: Spring Boot 版本选择 3.3.x + JDK 17

**选择**：Spring Boot 3.3.x + Java 17
**理由**：Spring Boot 2.x 已进入 EOL，3.x 是当前主线且支持 virtual threads（潜在性能优化）。Java 17 是 LTS 版本，生态成熟。
**备选**：Spring Boot 2.7.x + Java 11 — 更保守但缺乏未来兼容性。

### D2: sync_version 使用独立序列表而非自增

**选择**：独立 `sync_version_seq` 表，单行 `next_val BIGINT`，通过 `UPDATE ... SET next_val = next_val + 1` 原子递增获取。
**理由**：三张业务表各自的主键 id 自增互不相关，全局版本号需要跨表统一递增。独立序列表保证原子性（数据库行锁），比 Java 应用层的 `AtomicLong` 更可靠（支持多实例部署）。
**备选**：数据库自增字段 `AUTO_INCREMENT` — 各表独立，无法跨表统一。Redis INCR — 增加外部依赖，不适合当前规模。

### D3: 增量同步接口采用分页游标模式

**选择**：`GET /api/v1/sync/{resource}?since={version}&limit={n}`，按 sync_version ASC 排序，返回 `SyncResponse<T>` 含 `hasMore`、`nextSince` 字段。
**理由**：鹊桥可能拉取大量增量数据，分页避免单次查询超时或 OOM。`hasMore` + `nextSince` 允许鹊桥循环拉取直到无更多数据。
**备选**：全量返回 — 简单但不可靠，数据量大时可能 OOM。

### D4: 摄像头清单采用数据库存储 + Excel 导入

**选择**：核心存储为数据库表 `camera_config`，提供 `POST /api/v1/cameras/import` 接口接受 Excel 文件上传并批量 upsert。
**理由**：数据库存储支持 CRUD 和查询，Excel 导入作为批量初始化和日常维护的便捷工具。upsert（ON DUPLICATE KEY UPDATE）避免重复导入问题。
**备选**：仅 Excel 文件存储 — 并发和查询困难。仅数据库手动 INSERT — 维护效率低。

### D5: API Key 使用固定值的简化实现

**选择**：通过 `application.yml` 的 `enviro.api.key` 配置项定义固定 API Key，拦截器读取配置比对。
**理由**：Phase 1 不需要多租户/多 Key 管理，固定值简化实现。后续可扩展为数据库存储 + 缓存方案。
**备选**：数据库存储 API Key 表 — Phase 1 过度设计。

### D6: 统一 ApiResponse 响应体

**选择**：所有 API 返回 `{ "code": 200, "message": "success", "data": ... }`，通过 Spring `@RestControllerAdvice` 统一包装。
**理由**：一致的响应格式利于前端/鹊桥消费。`@RestControllerAdvice` 避免在每个 Controller 手动包装。
**备选**：直接返回业务对象 — 不一致，增加消费者解析成本。

## Data Flow（Phase 1 涉及的路径）

```
Phase 1 聚焦：同步路径的"被拉取侧"

写路径（暂不实现）：
    环保小脑 → [Phase 2 实现] → 入库（带 sync_version）

同步路径（Phase 1 提供被查询能力）：
    鹊桥定时拉取 ──→ GET /api/v1/sync/watermark     → 获取当前水位
                   ──→ GET /api/v1/sync/inspections?since=X&limit=N
                   ──→ GET /api/v1/sync/camera-results?since=X&limit=N
                   ──→ GET /api/v1/sync/ledger-records?since=X&limit=N
                   → 鹊桥本地写入自有数据库 [Phase 3 实现]

读路径和操作路径：Phase 1 不涉及
```

## Risks / Trade-offs

| 风险 | 缓解措施 |
|------|----------|
| **[R1] sync_version_seq 单点瓶颈**：所有写入竞争同一行锁 | Phase 1 单实例部署下不是问题；后续多实例可切换到 DB 序列的 `INCREMENT BY` 分批预取 |
| **[R2] 增量接口无数据**：Phase 1 仅有表结构无数据写入 | 接口按空结果返回（`data: [], hasMore: false`），Phase 2 写入数据后自动生效 |
| **[R3] API Key 硬编码不安全**：配置文件明文存储 | Phase 1 用 `application-dev.yml`（gitignore）；生产环境通过环境变量注入 |
| **[R4] Excel 导入大文件可能 OOM**：Apache POI 解析大 Excel 消耗内存 | 限制上传文件大小（5MB），分 Sheet 分批处理 |

## Open Questions

- [ ] MySQL 连接信息（host/port/db/user/password）— 开发环境默认 localhost:3306，具体配置写入 `application-dev.yml`
- [ ] API Key 具体值 — 开发阶段使用 `dev-api-key-2026`
- [ ] 摄像头清单初始数据来源 — 是否有现成 Excel 文件？还是从零录入？
