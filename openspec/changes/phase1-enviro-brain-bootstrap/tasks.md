## 1. Spring Boot 项目骨架 (enviro-brain)

- [x] 1.1 创建 `enviro-brain/` 目录结构（src/main/java/com/enviro/brain 分包）
- [x] 1.2 编写 `pom.xml`（Spring Boot 3.3.x、MyBatis、MySQL、POI、Lombok、Test）
- [x] 1.3 编写 `EnviroBrainApplication.java` 启动类
- [x] 1.4 编写 `application.yml` + `application-dev.yml`（端口、DB 连接、API Key、MyBatis 配置）
- [x] 1.5 验证 `mvn clean compile` 编译通过

## 2. 数据库 Schema (enviro-brain)

- [x] 2.1 编写 `src/main/resources/db/schema.sql`，包含 5 张表的 DDL
- [x] 2.2 inspection_records 表 DDL（含 sync_version + idx_sync_version）
- [x] 2.3 camera_results 表 DDL（含外键 + sync_version + idx_sync_version）
- [x] 2.4 ledger_records 表 DDL（含外键 + sync_version + idx_sync_version）
- [x] 2.5 camera_config 表 DDL（camera_code UNIQUE）
- [x] 2.6 sync_version_seq 表 DDL（单行初始值 1）
- [x] 2.7 在本地 MySQL 5.7.39 执行 schema.sql，5 表 + 全部索引 + 2 外键验证通过（修复 CHECK COMMENT 语法兼容 MySQL 5.7）

## 3. Entity + Mapper 层 (enviro-brain)

- [x] 3.1 创建 Entity 类：InspectionRecord、CameraResult、LedgerRecord、CameraConfig + HasSyncVersion 接口
- [x] 3.2 编写 MyBatis Mapper XML：InspectionRecordMapper.xml（CRUD + 按 sync_version 增量查询）
- [x] 3.3 编写 CameraResultMapper.xml（CRUD + 增量查询）
- [x] 3.4 编写 LedgerRecordMapper.xml（CRUD + 增量查询）
- [x] 3.5 编写 CameraConfigMapper.xml（CRUD + 按 camera_code 查询 + upsert）
- [x] 3.6 编写 SyncVersionMapper 接口 + XML（nextVersion，FOR UPDATE 原子操作）
- [x] 3.7 编写单元测试验证 Mapper 可用性（35 tests PASS，含 review 修复后的增量同步测试）

## 4. API Key 认证 (enviro-brain)

- [x] 4.1 创建 ApiKeyAuthInterceptor 拦截器类（构造注入 apiKey）
- [x] 4.2 实现 preHandle：校验 X-API-Key 头，白名单放行 /actuator/health、/error
- [x] 4.3 创建 WebMvcConfig，注册拦截器到 /api/** 路径（@Value 注入 + @Bean 创建）
- [x] 4.4 编写单元测试验证拦截逻辑（5 unit + 3 integration = 8 tests PASS）

## 5. SyncVersion 服务 + 全局 ApiResponse (enviro-brain)

- [x] 5.1 创建 SyncVersionService，封装 nextVersion() 调用（@Transactional + @Service）
- [x] 5.2 创建统一 ApiResponse<T> 响应 DTO（code、message、data，含 success/error 静态工厂）
- [x] 5.3 创建 SyncResponse<T> 响应 DTO（继承 ApiResponse，增加 hasMore、nextSince，of() 静态工厂）
- [x] 5.4 编写单元测试（4 ApiResponse + 5 SyncResponse + 2 SyncVersionService = 11 tests PASS）

## 6. 摄像头配置管理 (enviro-brain)

- [x] 6.1 创建 CameraConfigController（GET /api/v1/cameras 分页查询，data.items + total/page/size）
- [x] 6.2 实现 GET /api/v1/cameras/{cameraCode} 单项查询（404 时返回 ApiResponse.error）
- [x] 6.3 实现 POST /api/v1/cameras/import Excel 导入（POI 解析 + 批量 upsert，返回 ImportResult）
- [x] 6.4 实现 GET /api/v1/cameras/template 模板下载（XSSFWorkbook 生成含示例行模板）
- [x] 6.5 添加文件大小限制（5MB）和格式校验（.xlsx/.xls 白名单，camera_code 必填列校验）
- [x] 6.6 编写集成测试（9 Service + 7 Controller = 16 tests，全量 70 tests PASS）

## 7. 数据同步接口 (enviro-brain)

- [x] 7.1 创建 SyncController（GET /api/v1/sync/watermark）
- [x] 7.2 实现 GET /api/v1/sync/inspections?since=&limit= 增量分页查询
- [x] 7.3 实现 GET /api/v1/sync/camera-results?since=&limit= 增量分页查询
- [x] 7.4 实现 GET /api/v1/sync/ledger-records?since=&limit= 增量分页查询
- [x] 7.5 实现 limit 参数默认值 1000、最大值 5000 的校验
- [x] 7.6 编写集成测试

## 8. 全局异常处理 + 集成验证 (enviro-brain)

- [ ] 8.1 创建 @RestControllerAdvice 全局异常处理器
- [ ] 8.2 处理常见异常：MethodArgumentNotValidException → 400、Exception → 500
- [ ] 8.3 验证 `mvn spring-boot:run` 启动成功，/actuator/health 返回 UP
- [ ] 8.4 手动验证所有同步接口
- [ ] 8.5 手动验证 API Key 拦截
- [ ] 8.6 手动验证摄像头配置 CRUD 和 Excel 导入全流程

## 9. 文档与提交

- [ ] 9.1 更新 `.gitignore`（加入 target/、*.class、*.jar、application-dev.yml 等）
- [ ] 9.2 Git 提交：`git add enviro-brain/ && git commit -m "feat: Phase 1 - enviro brain bootstrap"`
- [ ] 9.3 推送到 GitHub
