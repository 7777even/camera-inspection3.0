## Context

Phase 3 已完成鹊桥数据同步层：定时从环保小脑拉取巡查数据写入鹊桥自有 `synced_*` 库（MySQL/H2），并对外提供 `/api/notify` 回调接收。脑机桌面端需以标准化方式消费这些数据并触发操作（巡检、下载台账），但直接对接环保小脑或数据库会绕过分层与统一鉴权。

因此在 queqiao 应用中内嵌一层 **MCP Server**（模型上下文协议），把"查询鹊桥库"与"转发环保小脑"两类能力封装为 5 个标准工具，经外部网关以 SSE 暴露给脑机端。

约束：
- Spring Boot 3.3.5 / Java 17；复用 Phase 3 的 DB 连接、RestTemplate、异常体系（`SyncClientException`）。
- 实际解析依赖为 **Spring AI 1.0.0 + MCP Java SDK 0.10.0**，其 `spring-webmvc` 模块仅支持 SSE 服务端传输（`WebMvcSseServerTransportProvider`），**不支持 streamable-http**。

## Goals / Non-Goals

**Goals:**
- 暴露 5 个 MCP 工具：3 查询（`getInspectionLedger` / `getCameraStatus` / `getInspectionSummary`）+ 2 操作（`triggerInspection` / `downloadLedgerDocx`）。
- 查询类直读鹊桥 `synced_*` 库，绝不穿透环保小脑。
- 操作类转发环保小脑对应 API，不可达/异常时返回友好错误而非抛出未处理异常。
- 提供可选 MCP 端点鉴权（默认关闭）。

**Non-Goals:**
- 不重新设计同步逻辑（沿用 Phase 3）。
- 不实现 streamable-http（SDK 0.10.0 不支持）。
- 不对接真实脑机端/环保小脑做端到端联调（留 Phase 5）。
- `enterprise` 入参为预留 no-op（当前 `synced_*` 无对应列），待 Phase 5 补数据模型。

## Decisions

**D1 嵌入 queqiao 应用（非独立模块 / 非 stdio）**
复用同一 Spring 上下文、DB、RestTemplate 与配置；通过 `ToolCallbackProvider` Bean 自动被 Spring AI MCP Server 拾取。相较独立模块省去网络跳转与重复配置。

**D2 传输采用 webmvc/SSE（非 streamable-http）**
Spring AI 1.0.0 中 `transport` 合法值为 `WEBMVC`/`WEBFLUX`；SDK 0.10.0 的 webmvc 仅提供 `WebMvcSseServerTransportProvider`。实测端点为 `GET /mcp/sse`（SSE 流，建立会话）与 `POST /mcp/message`（JSON-RPC 消息，带 `?sessionId=`）。注意：SDK 0.10.0 中 `base-url` 不会前缀到路由路径，仅用于 SSE 会话上下文。

**D3 操作类工具返回具体 DTO（非 Object/Map）**
Spring AI 1.0.0 的 `MethodToolCallbackProvider` 会**静默丢弃**返回 `Object`/`Map` 的 `@Tool` 方法。故两个操作类工具统一返回 `OperationResultView{ ok, message, data }`，与查询类各自视图 DTO 一致，确保 5 个工具全部注册。

**D4 查询/操作分流**
- 查询类：`EnviroInspectionQueryService` 调 Phase 3 既有 Mapper 读 `synced_*`，不请求环保小脑。
- 操作类：`EnviroInspectionForwardService` → `EnviroBrainForwardClient`（复用 `enviro-brain.base-url`/`api-key` 与 RestTemplate）→ 环保小脑 API；捕获 `RestClientException` 与 `HttpMessageConversionException` 包为 `SyncClientException`，service 层降级为友好 `Map`/`OperationResultView`。

**D5 可选鉴权**
`McpAuthInterceptor` 默认 `enabled=false` 直接放行；启用后仅对 `/mcp/**` 做 `X-API-Key` 校验（缺失/错误/空白配置均 401）。默认依赖外部网关做 Bearer 校验。

## Risks / Trade-offs

- **[Risk] SSE 非 streamable-http**：脑机端须使用 SSE 客户端连接 `/mcp/sse`，而非 streamable-http 客户端。→ 缓解：冒烟文档给出 Python SSE 联调示例；Phase 5 接入时按 SSE 格式配置。
- **[Risk] 操作类依赖环保小脑可用性**：脑机端调用 `triggerInspection`/`downloadLedgerDocx` 时环保小脑可能不可达。→ 缓解：友好降级返回 `ok=false`+message，异常不逃逸。
- **[Risk] 反序列化失败裸抛**：环保小脑返回结构损坏时 `HttpMessageConversionException` 非 `RestClientException` 子类。→ 缓解：客户端显式捕获并降级（已落地+单测）。
- **[Trade-off] `enterprise` 入参 no-op**：为接口稳定性预留，但当前不生效。→ 待 Phase 5 数据模型补齐后启用。
