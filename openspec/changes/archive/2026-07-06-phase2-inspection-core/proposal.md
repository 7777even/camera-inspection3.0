# Phase 2: 环保小脑巡检核心

> 基于 v3.0 方案第十四节实施路线图

## 为什么

Phase 1 完成了基础设施（Spring Boot 骨架、数据库、API Key 认证、同步接口、摄像头配置管理），但尚不能执行实际巡检。Phase 2 的目标是构建巡检执行引擎，让环保小脑真正能够每小时自动对危废仓库摄像头截图、质量检测、生成台账、推送飞书通知。

## 变更内容

### 新增能力

- **Python 截图调用**：Java ProcessBuilder 调用 camera_capture.py（RTSP 截图 + OpenCV 质量检测），CLI 参数传递海康凭据，stdout JSON 输出反序列化
- **巡检编排引擎**：InspectionService 完整流程编排——读摄像头配置 → 线程池并发截图 → 汇总统计 → 批量入库 → 台账生成 → 飞书通知
- **定时调度**：@Scheduled(cron="0 0 * * * ?") 每小时整点自动执行
- **手动触发**：POST /api/v1/inspections/trigger 支持脑机端/鹊桥手动触发
- **飞书通知**：RestTemplate POST 飞书 Webhook，发送 interactive 卡片（巡检概况 + 离线/异常详情）
- **台账生成**：LedgerService 筛选需登记的结果 → 写 ledger_records 表（sync_version 支持后续鹊桥同步）
- **鹊桥回调**（可选）：巡检完成后 POST JSON 通知鹊桥有新数据可同步

### 影响范围

- 新增 6 个 Service、2 个 Controller/Scheduler、1 个 DTO
- 扩展 InspectionRecordMapper（updateById）+ CameraResultMapper（batchInsert）
- 无 Breaking Changes，所有 Phase 1 接口和测试保持不变
- sync_version 机制已就绪，Phase 2 写入的数据自动纳入 Phase 1 同步接口

## 验收标准

- [x] 118 个测试全部通过，零回归（Phase 1 91 + Phase 2 27）
- [x] Maven package BUILD SUCCESS
- [ ] 部署后每小时自动执行巡检（需 MySQL + Python + 海康凭据环境）
- [ ] 飞书通知卡片内容正确（需配置 webhook-url）
