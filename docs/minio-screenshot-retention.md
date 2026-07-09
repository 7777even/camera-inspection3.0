# MinIO 截图有效期手动验证说明

本文档用于在没有自动化测试覆盖的「运行时」场景下，由人工对照 MinIO 真实桶与运行中的 `enviro-brain` 服务，验证 `MinioStorageService` 的 **对象 Key 方案 / 过期清理 / 安全边界 / 开关** 行为。

> 前置条件：本地或测试环境已启动 MinIO（含对应 bucket），且 `enviro-brain` 服务可连接（配置项 `enviro.minio.*` 已就绪）。以下操作均针对 **测试/预发** 环境，切勿在生产直接改 `retention-days=0`。

## 1. 每小时独立对象 Key（`name_HH.jpg`）

目标：同一摄像头在相邻整点各产生一张截图时，对象 Key 应带小时后缀且互不相同。

步骤：
1. 确保服务正常运行，`enviro.minio.cleanup.enabled=true`，`retention-days` 为正常值（如 7）。
2. 触发一次巡检（或等待一次定时抓拍），记录当前小时 `HH1`。
3. 在 MinIO 控制台 / `mc` 中确认桶内出现形如 `2026-07-09/cam-A_14.jpg` 的对象；或查询 `inspection_records.screenshot_path` 指向唯一 URL。
4. 等待到下一个整点（或手动再次触发），得到 `HH2 != HH1`。
5. 确认出现 `2026-07-09/cam-A_15.jpg`，且两条记录的 `screenshot_path` 各自指向不同 URL。

预期：同一摄像头在 `HH1` 与 `HH2` 的对象 Key 不同，互不可覆盖。

```bash
mc ls local/enviro-bucket/2026-07-09/ | grep cam-A
# 期望至少出现 cam-A_14.jpg 与 cam-A_15.jpg 两个对象
```

## 2. 清理触发（安全验证，临时设 `retention-days=0`）

目标：确认过期清理会真正删除对象，且删完立即恢复，避免误清。

步骤：
1. **临时** 将 `enviro.minio.retention-days` 设为 `0`。
2. 重启服务（或等待下一次 `@Scheduled` 清理周期）。
3. 观察日志出现 `[Minio] 清理过期截图 N 张`，其中 `N` 应等于桶内已有合法对象数（全部视为过期）。
4. 确认桶内对象已被删除（`mc ls` 为空或仅剩旧格式/非图片文件）。
5. **立刻把 `retention-days` 改回 7**，再次重启，避免后续误清。

预期：清理发生，日志计数正确；改回 7 后不再误删。

## 3. 安全边界：旧格式 / 非图片 Key 不被删除

目标：清理只针对 `name_HH.jpg` 这类合法且过期的对象，旧格式与异常文件应被跳过。

步骤：
1. 往桶里手动放入：
   - 一个旧格式 key：如 `2026-07-09/旧名.jpg`（无 `_HH` 后缀）。
   - 一两个非图片文件：如 `2026-07-09/note.txt`、`2026-07-09/data.json`。
   - 一个合法但过期的 `2026-07-09/cam-A_01.jpg`（配合 `retention-days=0` 测试用）。
2. 按第 2 节触发一次清理。
3. 观察日志，确认合法过期项被删除，而 `旧名.jpg`、`note.txt`、`data.json` **仍然存在**。

预期：清理日志显示仅删合法过期项；旧格式 / 非图片 key 永不被删除。

## 4. 开关：关闭清理不调用 `removeObjects`

目标：确认 `enviro.minio.cleanup.enabled=false` 时完全跳过清理。

步骤：
1. 将 `enviro.minio.cleanup.enabled` 设为 `false`。
2. 重启服务并等待至少一个清理周期。
3. 观察日志出现 `[Minio] 清理已禁用...跳过`，且全程 **没有** `[Minio] 清理过期截图` 日志，桶内对象数量不变。

预期：清理被跳过，`removeObjects` 不被调用，对象保留。

## 回归说明

以上 4 步对应的单测（`MinioStorageServiceTest` 9 个用例）已在 CI 中覆盖 key 解析、过期判定、select/delete 选择逻辑与开关路径；本文档用于在 **真实 MinIO + 运行服务** 下补充「端到端 / 副作用」层面的确认。
