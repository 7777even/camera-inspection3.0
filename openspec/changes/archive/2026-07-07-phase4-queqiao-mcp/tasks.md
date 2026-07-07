## 1. MCP 依赖与配置

- [x] 1.1 引入 `spring-ai-bom` 1.0.0、`spring-ai-starter-mcp-server-webmvc`（compile）、`spring-ai-starter-mcp-client`（test）到 `queqiao/pom.xml`
- [x] 1.2 在 `application.yml` 配置 `spring.ai.mcp.server`（enabled / transport=WEBMVC / sse-endpoint=/mcp/sse / sse-message-endpoint=/mcp/message）与 `queqiao.mcp.auth.enabled`（默认 false）

## 2. 查询类 Mapper 读方法与视图 DTO

- [x] 2.1 为 `SyncedInspectionRecordMapper` / `SyncedCameraResultMapper` / `SyncedLedgerRecordMapper` 增加查询读方法（按日期/区间/recordId/cameraCode/最新每摄像头等）
- [x] 2.2 新增视图 DTO：`InspectionLedgerView`、`CameraStatusView`、`InspectionSummaryView`（各含 `empty(...)` 工厂）

## 3. 查询类业务逻辑

- [x] 3.1 实现 `EnviroInspectionQueryService`：`getInspectionLedger` / `getCameraStatus` / `getInspectionSummary`，直读 `synced_*`，空结果返回 `empty(...)` 视图

## 4. 操作类转发与降级

- [x] 4.1 实现 `EnviroBrainForwardClient`（复用 RestTemplate + `enviro-brain.*`），转发 trigger/download 两个 API，捕获 `RestClientException` 与 `HttpMessageConversionException` 包为 `SyncClientException`
- [x] 4.2 实现 `EnviroInspectionForwardService`：`triggerInspection` / `downloadLedgerDocx`，捕获 `SyncClientException` 降级为友好 `Map`/`OperationResultView`

## 5. MCP 工具装配

- [x] 5.1 实现 `EnviroInspectionMcpTools`（5 个 `@Tool` 方法，委派 QueryService + ForwardService，返回具体 DTO）
- [x] 5.2 实现 `McpServerConfig`：`@Bean ToolCallbackProvider` 装配 5 个工具

## 6. MCP 集成测试

- [x] 6.1 工具注册测试：断言 5 个工具名注册且可调
- [x] 6.2 端点映射测试：断言 SSE 端点 `/mcp/sse` 与 `/mcp/message` 经 `RouterFunctionMapping` 已映射

## 7. 可选鉴权

- [x] 7.1 实现 `McpAuthInterceptor`（默认关闭，启用后对 `/mcp/**` 校验 `X-API-Key`）
- [x] 7.2 在 `WebMvcConfig` 注册该拦截器

## 8. 冒烟文档与全量测试

- [x] 8.1 编写 `docs/Phase4本地手动冒烟步骤.md`（含端点修正、SSE 联调示例、鉴权、H2 兜底）
- [x] 8.2 全量 `mvn test` 通过（49/49）
