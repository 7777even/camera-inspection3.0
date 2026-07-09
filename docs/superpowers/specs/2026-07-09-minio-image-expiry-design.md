# MinIO 截图按小时留痕 + 有效期自动清理设计

> 状态：设计稿（已通过用户审阅，待出实施计划）
> 日期：2026-07-09
> 上游：本轮对话（用户反馈"简单覆盖会导致调不出某小时截图"）
> 目标：截图不再跨小时互相覆盖，可随时按 URL 调出任一小时的截图；同时设有效期，过期对象由应用定时自动清理，避免无限堆积

---

## 1. 背景与问题

当前 `MinioStorageService` 的对象键为 `{prefix}/{yyyy-MM-dd}/{safeName}.jpg`，**不含时分秒**。同一摄像头当天多次巡检落到同一 key，`putObject` 默认覆盖旧对象，效果是"当天一张图、最新覆盖"。

问题：当需要回溯某一小时的截图（如"调出 14:00 的图"）时，拿到的是最新一次巡检覆盖后的图（如 15:00 的），历史小时截图已丢失。

用户决策（已确认）：
- **保留时长**：做成 yml 配置项 `enviro.minio.retention-days`，**默认 7 天**。
- **清理机制**：**应用内 `@Scheduled` 定时清理**（本仓库 MinIO 为外部桶，`docker-compose.yml` 中无 MinIO 服务，服务端 ILM 需外部管理员权限、沙箱内无法验证，故不选）。

本方案**取代**原先"当天覆盖"的需求。

---

## 2. 非目标（明确排除）

- 不改变台账 docx 生成逻辑（台账插图用的是 `localScreenshotPath`，本来就是当次巡检本地文件，不受影响）。
- 不改变 `inspection_records` 表结构；`screenshot_path` 仍存唯一 URL。
- 不引入 MinIO 服务端 ILM / `mc ilm` 配置。
- 不做对象级"到期精确时刻删除"（S3/MinIO 标准无此能力），以"天"为粒度做过期判断。
- 不同清理机制（ILM）不做——仅应用内清理。

---

## 3. 对象键方案（核心）

把"小时"写入 key，使同一摄像头每小时一张**独立**对象，互不打覆盖：

```
{prefix}/{yyyy-MM-dd}/{safeName}_{HH}.jpg

例：
  2026-07-09/三菱化学危废仓库1_14.jpg   ← 14 点那次巡检
  2026-07-09/三菱化学危废仓库1_15.jpg   ← 15 点那次巡检（与 14 点互不打覆盖）
```

- `safeName` 沿用现有清洗规则：仅剔除路径分隔符、`/` `:` `*` `?` `"` `<` `>` `|` `%` `#` 与控制字符（`\x00-\x1f`）及空格，保留中文等多语言字符。
- `HH` 取上传时刻的小时（`LocalDateTime.now().format("HH")`），两位补零。
- 同小时内若重复巡检（如手动重跑）仍会覆盖——这是可接受的，要解决的是**跨小时**误调。
- DB 中 `inspection_records.screenshot_path` 存的是该次巡检对象的**唯一 URL**，后续按 URL 取回即当时截图，不再串到别的小时。

---

## 4. 清理任务（应用内定时）

新增轻量调度类 `MinioCleanupScheduler`（`@Component`，`@Scheduled`），调用 `MinioStorageService.cleanupExpiredObjects()`：

1. 按 `prefix` 递归 `listObjects` 列出桶内全部对象；
2. 用正则 `(\d{4}-\d{2}-\d{2})/.+_(\d{2})\.jpg$` 从 key 解析出"日期 + 小时"，构造 `LocalDateTime`，算出对象年龄（相对 `now`）；
3. 若 `年龄 > retention-days` → 收集进批量删除；
4. 用 `removeObjects` 一次性批量删除；
5. **解析失败的对象（旧格式每日键、其他文件）一律跳过，绝不误删**。

调度与兜底：
- 默认 `cron: "0 0 2 * * ?"`（每天 02:00 跑一次）；
- 应用启动时（`ApplicationRunner`）先跑一次 catch-up，避免错过调度窗口；
- 受 `enviro.minio.cleanup.enabled` 开关控制，可一键关闭。

---

## 5. 配置项（application.yml / application-prod.yml）

```yaml
enviro:
  minio:
    endpoint: ${ENVIRO_MINIO_ENDPOINT}
    bucket: ${ENVIRO_MINIO_BUCKET}
    prefix: ${ENVIRO_MINIO_PREFIX:}
    retention-days: 7            # 新增：有效期天数（默认 7）
    cleanup:
      enabled: true               # 新增：清理开关
      cron: "0 0 2 * * ?"        # 新增：每天 02:00
```

> `retention-days` 也支持通过环境变量 `ENVIRO_MINIO_RETENTION_DAYS` 注入（便于部署期覆盖）。

---

## 6. 对既有模块的影响

| 模块 | 影响 |
|------|------|
| 台账 docx（`LedgerService`） | 无影响，插图用 `localScreenshotPath` |
| `inspection_records.screenshot_path` | 无结构变更；存的 URL 变为带 `_HH` 的唯一键，可按时取回 |
| 旧数据（无 `_HH` 后缀的每日覆盖键） | 清理逻辑解析失败会跳过，**不会被误删**；可日后手动一次性清理 |
| `MinioStorageService` | 仅改 key 生成 + 新增清理方法，上传主流程不变 |

---

## 7. 测试

- **单测 `buildObjectKey()`**：同一摄像头、不同小时 → 生成两个不同 key（验证不覆盖）；验证 `safeName` 清洗规则仍生效。
- **单测 `isExpired()`**：解析 key 内日期+小时；边界验证——第 7 天当天不过期、第 8 天 00:00 后过期；非法 key 返回"不过期/跳过"。
- **集成单测（`@SpringBootTest` + mock `MinioClient`）**：
  - 注入一组混合 key（过期的、未过期的、旧格式解析失败的）；
  - 验证：只删除过期项、`removeObjects` 被调用且仅含过期 key、解析失败项被跳过、开关 `enabled=false` 时整体不执行。

---

## 8. 改动文件清单

| 文件 | 改动 |
|------|------|
| `enviro-brain/.../service/MinioStorageService.java` | key 加 `_HH`；新增 `cleanupExpiredObjects()`、`isExpired()`、`parseTimestampFromKey()` |
| 新增 `enviro-brain/.../scheduler/MinioCleanupScheduler.java` | `@Component` + `@Scheduled` + `ApplicationRunner` catch-up |
| `enviro-brain/src/main/resources/application.yml` | 加 `retention-days` / `cleanup` |
| `enviro-brain/src/main/resources/application-prod.yml` | 同上 |
| 新增 1–2 个单测 | `MinioStorageServiceTest`、`MinioCleanupSchedulerTest` |
| `docs/`（可选） | 补一段 MinIO 截图有效期说明 |

---

## 9. 实施要点 / 风险

- **风险**：清理任务若正则不匹配会漏删（对象滞留）——通过"解析失败即跳过 + 单测覆盖正则"降低；滞留不致命，只是占空间。
- **风险**：误删有效对象——仅当 key 能被正确解析且年龄超阈值才删，旧格式跳过，安全。
- **回滚**：`enabled: false` 可立即关停清理；key 格式变更后，新旧键并存，旧键不影响新逻辑。
- **验证**：本地起 MinIO（或复用外部桶测试前缀）跑一轮巡检，确认 14 点/15 点各生成一个独立对象；把 `retention-days` 临时设为 0 验证清理能触发删除（再调回 7）。
