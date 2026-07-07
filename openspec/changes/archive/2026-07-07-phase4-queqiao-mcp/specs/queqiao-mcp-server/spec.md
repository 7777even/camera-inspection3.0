# Capability: queqiao-mcp-server

鹊桥 MCP 封装层：把鹊桥的查询与操作能力暴露为 5 个标准 MCP 工具，基于 Spring AI MCP（webmvc/SSE）嵌入 queqiao 应用，供脑机桌面端经网关统一接入。

## ADDED Requirements

### Requirement: 注册 5 个标准 MCP 工具
鹊桥 MCP Server SHALL 注册 5 个工具：`getInspectionLedger`、`getCameraStatus`、`getInspectionSummary`（查询类）与 `triggerInspection`、`downloadLedgerDocx`（操作类），通过 `ToolCallbackProvider` Bean 暴露，供脑机端经 MCP 协议调用。

#### Scenario: 服务启动后工具可见
- **WHEN** 鹊桥应用（MCP Server 启用）启动完成
- **THEN** 通过 MCP 协议可列出且仅列出这 5 个工具，名称分别为 `getInspectionLedger`、`getCameraStatus`、`getInspectionSummary`、`triggerInspection`、`downloadLedgerDocx`

### Requirement: 查询类工具直读鹊桥自有库
查询类工具 SHALL 仅读取鹊桥自有 `synced_*` 表（经 Phase 3 既有 Mapper），不得向环保小脑发起请求。`getInspectionLedger` 返回指定日期巡查台账；`getCameraStatus` 返回摄像头状态；`getInspectionSummary` 返回区间巡检汇总（在线率、最差记录日、频繁离线摄像头排名）。空结果须返回带说明信息的视图而非报错。

#### Scenario: 查询不穿透环保小脑
- **WHEN** 调用任一查询类工具
- **THEN** 系统仅访问鹊桥 `synced_inspection_record` / `synced_camera_result` / `synced_ledger_record` 表，不向环保小脑发起任何 HTTP 请求

#### Scenario: 无数据返回友好视图
- **WHEN** 查询日期在 `synced_*` 表中无对应记录
- **THEN** 返回含说明信息（如「当日暂无同步数据」）的结构化视图，HTTP/MCP 调用成功而非异常

### Requirement: 操作类工具转发环保小脑并友好降级
操作类工具 SHALL 经 `EnviroBrainForwardClient` 转发环保小脑对应 API（`triggerInspection` → `POST /api/v1/inspections/trigger`；`downloadLedgerDocx` → `GET /api/v1/ledger/{id}/download`）。当环保小脑不可达、返回非 2xx、响应体为 null 或结构损坏时，系统 SHALL 捕获并以友好结果（`ok=false` + message）降级返回，不得抛出未处理异常逃逸到 MCP 工具层。

#### Scenario: 环保小脑正常时转发成功
- **WHEN** 环保小脑可达且返回 2xx 有效载荷
- **THEN** 操作类工具返回成功结果（`ok=true`）并携带业务数据

#### Scenario: 环保小脑不可达或异常时降级
- **WHEN** 环保小脑不可达、返回非 2xx、data 为 null 或响应体无法反序列化
- **THEN** 操作类工具返回 `ok=false` 且含说明 message 的友好结果，调用方收到结构化失败而非未处理异常

### Requirement: 以 SSE 暴露 MCP 端点
鹊桥 MCP Server SHALL 以 webmvc/SSE 传输暴露端点：在端口 8081 提供 `GET /mcp/sse`（SSE 流，建立会话/接收服务端消息）与 `POST /mcp/message`（JSON-RPC 消息通道，携带 `?sessionId=`）。端点与既有 `/api/notify` 同进程共存；`base-url` 配置不前缀到实际路由路径。

#### Scenario: SSE 端点可探活
- **WHEN** 向 `GET /mcp/sse` 发起 SSE 连接
- **THEN** 建立长连接并返回含 `sessionId` 的 `endpoint` 事件，确认 MCP 端点已映射

### Requirement: 可选 MCP 端点鉴权
系统 SHALL 提供可选 MCP 端点鉴权拦截器，默认关闭（`queqiao.mcp.auth.enabled=false`）直接放行；当启用并配置 `queqiao.mcp.auth.api-key` 时，对 `/mcp/**` 请求校验 `X-API-Key`，缺失/错误/空白配置均返回 401。

#### Scenario: 默认关闭直接放行
- **WHEN** `queqiao.mcp.auth.enabled=false`（默认）
- **THEN** 对 `/mcp/**` 的请求不经 API-Key 校验直接放行

#### Scenario: 启用且密钥校验失败返回 401
- **WHEN** `queqiao.mcp.auth.enabled=true` 且请求缺少或携带错误的 `X-API-Key`
- **THEN** 返回 HTTP 401，不进入工具处理
