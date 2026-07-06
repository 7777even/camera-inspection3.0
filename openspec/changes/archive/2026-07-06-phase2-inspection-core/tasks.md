# Phase 2 实施任务清单

## 0. 环境准备

- [x] 0.1 复制 Python camera_capture.py + capture.py 到 enviro-brain/scripts/
- [x] 0.2 复制 DOCX 台账模板到 enviro-brain/templates/
- [x] 0.3 新增 application.yml 配置项（hikvision/python/screenshots/ledger/feishu/queqiao/inspection）

## 1. CameraCaptureResult DTO

- [x] 1.1 创建 CameraCaptureResult.java（status/qualityScore/screenshotPath/errorMsg/captureTime/retryUsed/qualityDetail）
- [x] 1.2 编写 CameraCaptureResultTest（2 tests）

## 2. CaptureService

- [x] 2.1 创建 CaptureService.java（@Value 注入 11 项配置 + buildCommand + parseResult + capture）
- [x] 2.2 编写 CaptureServiceTest（5 tests：命令构建验证 + JSON 解析 + 错误处理）

## 3. FeishuNotifyService

- [x] 3.1 创建 FeishuNotifyService.java（RestTemplate POST 飞书卡片 JSON）
- [x] 3.2 编写 FeishuNotifyServiceTest（4 tests：正常推送/空 URL 跳过/不可达不抛/卡片格式）

## 4. LedgerService

- [x] 4.1 创建 LedgerService.java（generateAndSave + shouldRegisterToLedger 筛选逻辑）
- [x] 4.2 编写 LedgerServiceTest（6 tests：空列表/null/写入/筛选规则）

## 5. InspectionService 核心编排

- [x] 5.1 Mapper 扩展：InspectionRecordMapper.updateById + CameraResultMapper.batchInsert
- [x] 5.2 创建 InspectionService.java（@Transactional 8 步编排 + ThreadPoolExecutor 并发）
- [x] 5.3 编写 InspectionServiceTest（5 tests：完整流程/部分失败/全部失败/空列表/统计正确）

## 6. 定时调度 + 手动触发

- [x] 6.1 创建 InspectionScheduler.java（@Scheduled cron 每小时整点）
- [x] 6.2 创建 InspectionController.java（POST /trigger → 202）
- [x] 6.3 编写 InspectionControllerTest（2 tests：trigger 202 + 缺 API Key 401）

## 7. 鹊桥回调（可选）

- [x] 7.1 创建 QueqiaoNotifyService.java（RestTemplate POST 鹊桥回调）
- [x] 7.2 注入 InspectionService + 末尾调用
- [x] 7.3 编写 QueqiaoNotifyServiceTest（3 tests）+ 更新 InspectionServiceTest

## 8. 最终验证

- [x] 8.1 全量 118 tests PASS，零回归
- [x] 8.2 Maven package BUILD SUCCESS
- [x] 8.3 更新 tasks.md
