# Phase 4 设计：鹊桥 MCP 封装层

> 状态：设计稿（待用户审阅）
> 日期：2026-07-07
> 上游：Phase 3 鹊桥数据同步层（已完成，21 个测试全绿）
> 下游：Phase 5 验收上线（真实脑机端 / 环保小脑联调）

---

## 1. 目标与定位

在现有 **queqiao** Spring Boot 应用内嵌入一个 **MCP Server**，把 Phase 3 已同步到鹊桥自有数据库（`synced_*`）的巡查数据，以及转发环保小脑的操作能力，封装成标准 MCP 工具，供**脑机桌面端**通过自然语言调用。

- 脑机端**只访问鹊桥**，不直接调环保小脑（方案 v3.0 核心约束）。
- 查询类工具直接读鹊桥自有库，**不穿透环保小脑**，天然满足「弱依赖 / 同步失败仍可返回历史数据」。
- 操作类工具在环保小脑**可达时**转发，不可达时返回友好错误而非崩溃。

**本阶段不做的**：真实脑机端联调、真实环保小脑 forward 联调、事件驱动回调（QueqiaoNotifyService，Phase 3 可选项）——均留到 Phase 5。

---

## 2. 非目标（明确排除）

- 不新建独立部署单元（MCP 嵌入 queqiao 单进程）。
- 不手写 MCP 协议（采用 Spring AI MCP 框架）。
- 不在本阶段对接真实环保小脑 / 脑机端做端到端冒烟。
- 不新增 `synced_*` 表结构（Phase 3 已定稿）。

---

## 3. 架构

```
脑机桌面端 ──HTTPS──> 外部网关(Bearer 令牌) ──> 鹊桥 queqiao :8081
                                                │
                          /mcp/enviro-inspection  (MCP Streamable HTTP 传输)
                                                │
                     EnviroInspectionMcpTools  (5 个 @Tool 方法)
                        ├─ 查询类 → EnviroInspectionQueryService → 已有 Mapper → synced_* 库
                        └─ 操作类 → EnviroInspectionForwardService → EnviroBrainForwardClient
                                                              → RestTemplate → 环保小脑 API
                                                │
                 复用 Phase 3 资产：DB 连接 / EnviroBrainSyncClient / RestTemplateConfig /
                                    ApiKeyAuthInterceptor / application.yml(enviro-brain.*)
```

- 传输：**Streamable HTTP（SSE）**，端点 `/mcp/enviro-inspection`，与现有 `/api/notify` 共存于 8081。
- 框架：**Spring AI MCP**，`spring-ai-mcp-server-webmvc-spring-boot-starter`（1.0.x，兼容 Spring Boot 3.3.5 / Java 17）。
- MCP 端点自身鉴权：默认**开放**（外部网关已做 Bearer 校验）；预留可选 `McpAuthInterceptor`（API-Key / Bearer），默认关闭，可配置开启。

---

## 4. 工具清单（5 个）

### 4.1 查询类（直接读 `synced_*`，不穿透环保小脑）

#### `get_inspection_ledger`
- **入参**：`date`（可选，YYYY-MM-DD，默认当天）、`enterprise`（可选，企业/园区名）、`status`（可选：`online`/`offline`/`abnormal`）
- **出参**：当日/指定日巡查台账 —— `inspection_records` 汇总 + 该轮下 `camera_results` 明细 + `ledger_records`；附 `synced_at` 数据时效
- **数据源**：`SyncedInspectionRecordMapper` + `SyncedCameraResultMapper` + `SyncedLedgerRecordMapper`
- **空结果**：返回空结构 + 提示「当日暂无同步数据」

#### `get_camera_status`
- **入参**：`camera_name`（可选）、`enterprise`（可选）、`history_days`（可选，默认 7）
- **出参**：摄像头最新快照（status / quality_score / screenshot_path / error_message）+ 近 N 天状态历史
- **数据源**：`SyncedCameraResultMapper`（按 `camera_code` / `record_id` 聚合，结合 `synced_inspection_records` 的日期维度）
- **空结果**：返回空结构 + 提示

#### `get_inspection_summary`
- **入参**：`start_date`、`end_date`（YYYY-MM-DD，必填）
- **出参**：区间内在线率（online/total）、最差记录日（offline/abnormal 最高）、频繁离线摄像头排名（TOP N）
- **数据源**：`SyncedInspectionRecordMapper` 区间聚合 + `SyncedCameraResultMapper` 按摄像头分组计数
- **空结果**：返回空结构 + 提示

### 4.2 操作类（转发环保小脑，仅当其可达时）

#### `trigger_inspection`
- **入参**：`reason`（可选，触发原因）
- **出参**：环保小脑返回的任务 ID / 受理回执
- **调用**：`EnviroBrainForwardClient` → `POST {enviro-brain.base-url}/api/v1/inspections/trigger`
- **不可达**：捕获 `SyncClientException` / `RestClientException`，返回「环保小脑暂不可用，请稍后重试」
- **鉴权**：复用 `enviro-brain.api-key`（与同步层同一凭证，`ApiKeyAuthInterceptor` 思路）

#### `download_ledger_docx`
- **入参**：`inspect_id`（必填，巡查记录 ID）
- **出参**：台账 Word 文档下载地址 / 资源引用（`docx_path` 或文档内容）
- **调用**：`EnviroBrainForwardClient` → `GET {enviro-brain.base-url}/api/v1/ledger/{id}/download`
- **不可达**：同 trigger_inspection 友好降级

---

## 5. 组件边界（新增 / 修改）

### 新增文件
| 文件 | 职责 |
|------|------|
| `config/McpServerConfig.java` | 注册 MCP Server（端点、传输、开启方式）+ 装配 5 个 tool bean |
| `mcp/EnviroInspectionMcpTools.java` | 5 个 `@Tool` 方法，参数校验与响应组装（方式 1：集中） |
| `service/EnviroInspectionQueryService.java` | 查询类业务逻辑，调用现有 Mapper，新增 read 方法 |
| `service/EnviroInspectionForwardService.java` | 操作类转发编排，调用 `EnviroBrainForwardClient` |
| `client/EnviroBrainForwardClient.java` | 复用 `RestTemplateConfig`，封装 trigger / download 两个 HTTP 调用 |
| `config/McpAuthInterceptor.java` | （可选）`/mcp/**` 的 API-Key / Bearer 校验，默认关闭 |
| Mapper read 方法 | `selectByInspectDate`、`selectCameraByInspect(enterprise,status,historyDays)`、`selectLedgerByInspectId`、`selectSummaryBetween(start,end)`、摄像头离线分组计数 |

### 修改文件
| 文件 | 变更 |
|------|------|
| `src/main/resources/application.yml` | 增加 `spring.ai.mcp.server`（`enabled`、`base-url`/path、`transport=streamable-http`）；`queqiao.mcp.auth.enabled`（默认 false） |
| `src/main/resources/application-dev.yml` | 本地 MCP 调试端口/路径（沿用 8081） |
| 对应 `*Mapper.xml` | 新增 read 方法 SQL（H2 兼容：`NOW()`→`CURRENT_TIMESTAMP`、`ON DUPLICATE`→`MERGE INTO`，遵循 Phase 3 已验证写法） |

> 组织方式采用**方式 1**（单 `EnviroInspectionMcpTools` 集中 5 个工具）。若后续工具增多，可平滑拆分为 `QueryMcpTools` / `ForwardMcpTools`（方式 2），不影响协议层。

---

## 6. 数据流

- **查询类**：MCP `tools/call` → `EnviroInspectionMcpTools` → `QueryService` → Mapper → `synced_*` → 组装 JSON → 返回（响应体含 `synced_at` 时效字段）
- **操作类**：MCP `tools/call` → `EnviroInspectionMcpTools` → `ForwardService` → `EnviroBrainForwardClient`（RestTemplate）→ 环保小脑 API → 返回任务 ID / 文档资源

---

## 7. 错误处理 / 容错

- 查询类：`synced_*` 有数据即返回；无数据返回**空结构 + 提示语**，**不抛 5xx**。
- 操作类：环保小脑不可达 → 捕获异常，返回友好错误文案，MCP 调用**不崩溃**（对应方案 12.1 弱依赖）。
- MCP 协议级错误（参数缺失、未知 tool 等）由 Spring AI MCP 框架统一处理并返回标准错误结构。
- 参数校验失败（如 `get_inspection_summary` 缺 `start_date`）：返回明确参数错误提示。

---

## 8. 测试策略（验收口径：代码 + 集成测试全绿）

1. **服务层单测**
   - `EnviroInspectionQueryServiceTest`：mock Mapper，覆盖筛选参数组合、空结果、聚合 summary 计算。
   - `EnviroInspectionForwardServiceTest`：mock `EnviroBrainForwardClient`，覆盖成功受理 + 环保小脑不可达降级。
2. **Mapper 测试**
   - 新增 read 方法走 H2，沿用 Phase 3 `AbstractQueqiaoTest` 的 `@DynamicPropertySource` 独立库名隔离。
3. **MCP 集成测试（关键，验证协议层）**
   - `EnviroInspectionMcpIntegrationTest`：用 Spring AI `McpClient` + 测试传输（in-process / direct），真实走 `initialize → tools/list → tools/call get_inspection_ledger`，断言返回结构与字段；并验证 `tools/list` 返回 5 个工具。
4. **不依赖**真实环保小脑（ForwardService 用 mock）/ 真实脑机端。
5. **文档**：`docs/` 下新增「Phase 4 本地手动冒烟步骤」，留给 Phase 5 真机联调参考（启动 8081 → 用 MCP 客户端连 `/mcp/enviro-inspection` → 调用 5 个工具）。

> 目标：Phase 4 结束时 `mvn test`（经 maven-windows-build 流程）全绿，新增测试与 Phase 3 的 21 个测试共存不冲突。

---

## 9. 配置 & 部署

- `application.yml` 新增（示意）：
  ```yaml
  spring:
    ai:
      mcp:
        server:
          enabled: true
          base-url: /mcp
          transport: streamable-http   # 端点 /mcp/enviro-inspection
  queqiao:
    mcp:
      auth:
        enabled: false                 # 默认开放，网关已做 Bearer
  ```
- 端点 `/mcp/enviro-inspection` 与 `/api/notify` 同进程共存，端口 8081。
- 脑机端接入配置（方案 11.1）不变：`mcpServers.enviro-inspection.url = https://<网关>/mcp/enviro-inspection` + `Authorization: Bearer <令牌>`。

---

## 10. 已知限制 / 后续

- 真实脑机端联调、真实环保小脑 forward 联调 → Phase 5。
- Phase 3 可选项「事件驱动回调 QueqiaoNotifyService」未做；Phase 4 操作类依赖 Phase 3 定时同步把数据带回鹊桥。
- MCP 端点自身鉴权默认开放；若上线要求鹊桥侧也加一层 API-Key，Phase 5 开启 `queqiao.mcp.auth.enabled=true` 并配置拦截器即可。
- Spring AI MCP 具体版本在实现阶段锁定为与 Boot 3.3.5 兼容的 1.0.x，依赖冲突在 writing-plans / 实施时验证。

---

## 11. 已决议事项（来自澄清）

- Q1 落地方式：**嵌入 queqiao 应用**（Spring AI MCP，webmvc/Streamable HTTP，`/mcp/enviro-inspection`，复用 DB+service）。
- Q2 验收口径：**代码 + 集成测试全绿**；真实联调留 Phase 5。
- 工具集：**5 个**（3 查询 + 2 操作），与方案 v3.0 七/九节一致。
- 操作类转发：复用 `RestTemplateConfig` + `enviro-brain.api-key` 凭证。
- MCP 端点鉴权：默认开放，预留可配拦截器。
