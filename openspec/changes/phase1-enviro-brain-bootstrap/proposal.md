## Why

环保小脑是整个巡检系统的数据生产者与定时调度核心，必须先搭建其基础设施（项目骨架、数据库、认证、同步接口），后续 Phase 2 巡检核心逻辑和 Phase 3 鹊桥同步才能在此基础上构建。Phase 1 完成后，环保小脑即具备"可运行、可认证、可被同步查询"的基线能力。

## What Changes

- **创建 Spring Boot 3.x 项目骨架**（Maven 管理依赖，Java 17+），项目子目录 `enviro-brain/`
- **设计并创建 4 张 MySQL 表**：inspection_records、camera_results、ledger_records、sync_version_seq —— 每张业务表含 `sync_version` 字段，索引 `idx_sync_version`，支持鹊桥增量拉取
- **实现 `sync_version_seq` 全局递增序列服务**：原子获取下一个版本号，写入每条入库记录
- **实现 API Key 认证拦截器**：对 `/api/**` 路径校验 `X-API-Key` 请求头，拦截非法请求返回 401
- **实现健康检查端点**：`GET /actuator/health` 受认证保护，返回服务状态
- **实现摄像头清单读取**：数据库存储摄像头配置（camera_code、camera_name、enterprise、rtsp_url 等），支持从 Excel 批量导入
- **实现数据同步接口**（供 Phase 3 鹊桥拉取）：
  - `GET /api/v1/sync/watermark` — 返回当前全局最大 sync_version
  - `GET /api/v1/sync/inspections?since={version}` — 增量查询巡检记录
  - `GET /api/v1/sync/camera-results?since={version}` — 增量查询摄像头结果
  - `GET /api/v1/sync/ledger-records?since={version}` — 增量查询台账记录

## Capabilities

### New Capabilities

- `enviro-brain-project`: Spring Boot 3.x 项目骨架，包含 Maven 配置、启动入口、application.yml 分层配置
- `database-schema`: 4 张 MySQL 表的 DDL 定义，含 sync_version 字段、索引、MyBatis Mapper 映射
- `sync-version-seq`: 全局递增版本号服务（sync_version_seq 表 + 原子获取），保障增量同步的可靠水位
- `api-auth`: API Key 认证拦截器，对 `/api/**` 路径强制校验 X-API-Key，忽略 /actuator/health 和 /error
- `camera-config`: 摄像头清单的数据库存储与 Excel 批量导入能力
- `data-sync-api`: 同步水位查询 + 三张业务表的增量分页查询接口，按 sync_version 升序返回

### Modified Capabilities

无（项目从零开始，不存在已有 capability）

## Impact

- **代码层面**：`enviro-brain/` 目录下新建完整 Spring Boot 项目，约 20+ 文件（Java 类 + XML + YAML + SQL）
- **数据库层面**：需要在 MySQL 中创建 `enviro_brain` 数据库及 4 张表，DDL 以 SQL 脚本形式纳入 `src/main/resources/db/`
- **依赖层面**：Maven 引入 spring-boot-starter-web、mybatis-spring-boot-starter、mysql-connector-j、poi-ooxml（Excel 读取）、lombok
- **架构影响**：仅涉及环保小脑层，鹊桥和脑机端不受影响
- **sync_version 兼容性**：三张业务表均设计 `sync_version BIGINT` 字段 + `idx_sync_version` 索引，增量查询按 `WHERE sync_version > #{since} ORDER BY sync_version ASC LIMIT #{limit}` 实现，为 Phase 3 鹊桥增量同步提供数据契约
- **同步接口标记**：4 个接口均为"同步接口（鹊桥拉取）"，非"操作转发接口"
