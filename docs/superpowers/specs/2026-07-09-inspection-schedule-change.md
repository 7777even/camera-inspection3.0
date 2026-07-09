# 巡检调度改为每日 9:00 / 15:00（yml 可配置）

- 日期：2026-07-09
- 状态：已与用户确认设计，进入实施

## 背景
原 `InspectionScheduler` 用硬编码 cron `0 0 * * * ?`，每小时整点触发一次摄像头巡检。
用户要求改为**每天上午 9:00 和下午 15:00** 各巡检一次，并把 cron 做成 yml 可配置（与 MinIO 清理 `enviro.minio.cleanup.cron` 风格一致）。

## 决策
- 巡检 cron 表达方式：`0 0 9,15 * * ?`（秒 0、分 0、时 9 与 15、每日）。
- 通过 `${enviro.inspection.cron:...}` 注入，缺省 `0 0 9,15 * * ?`；生产环境可用环境变量 `ENVIRO_INSPECTION_CRON` 覆盖。
- 方法 `hourlyInspection` 更名为 `scheduledInspection`（语义不再"每小时"）。
- **不改动** `MinioCleanupScheduler`（每日 02:00 清理过期截图功能保持独立、不受影响）。

## 受影响文件
1. `enviro-brain/src/main/java/com/enviro/brain/scheduler/InspectionScheduler.java`
   - `@Scheduled(cron = "${enviro.inspection.cron:0 0 9,15 * * ?}")`
   - 方法重命名 `hourlyInspection` → `scheduledInspection`
2. `enviro-brain/src/main/resources/application.yml`
   - `enviro.inspection` 下新增 `cron: ${ENVIRO_INSPECTION_CRON:0 0 9,15 * * ?}`
3. `enviro-brain/src/main/resources/application-prod.yml`
   - `enviro.inspection` 下新增 `cron: ${ENVIRO_INSPECTION_CRON:0 0 9,15 * * ?}`

## 验证
- `mvn.cmd -s ci-settings.xml test` 全量通过（变更不影响既有 129 个测试）。
- `mvn.cmd -s ci-settings.xml package` BUILD SUCCESS。
- 人工核对：应用启动后日志应在 09:00 / 15:00 出现 `[Scheduler] 定时巡检触发`（沙箱无法连内网摄像头，仅确认调度注册与配置生效，真实抓拍需目标环境回归）。

## 回链
> 决策依据详见：本次对话用户确认"做成 yml 可配置 (推荐)"，巡检时间定为每天 9:00 / 15:00。
