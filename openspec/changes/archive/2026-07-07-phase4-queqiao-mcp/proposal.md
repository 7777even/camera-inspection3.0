## Why

脑机桌面端需要以标准化方式调用鹊桥的数据与操作能力。Phase 3 已把环保小脑数据同步进鹊桥自有 `synced_*` 库并完成定时同步，但缺少对外暴露的标准接口；让脑机端直连环保小脑或直读数据库都会破坏分层边界与统一鉴权。Phase 4 把鹊桥封装为 MCP Server，对外暴露 5 个标准工具，使脑机端可经外部网关统一接入。

## What Changes

- 新增鹊桥 MCP 封装层（嵌入现有 queqiao 应用，端口 8081），基于 Spring AI MCP 1.0.0 + MCP Java SDK 0.10.0（webmvc/SSE 传输）。
- 暴露 5 个 MCP 工具：3 个查询类（读鹊桥 `synced_*` 库，不穿透环保小脑）+ 2 个操作类（转发环保小脑，不可达时友好降级）。
- 暴露 SSE 传输端点 `GET /mcp/sse`（SSE 流）+ `POST /mcp/message`（JSON-RPC 消息）。
- 新增可选 MCP 端点鉴权拦截器（默认关闭，可配置 `X-API-Key`）。

## Capabilities

### New Capabilities
- `queqiao-mcp-server`: 鹊桥 MCP 封装层，定义 5 个标准工具、SSE 传输端点、查询/操作分流与可选鉴权。

### Modified Capabilities
（无 —— Phase 4 仅新增能力，不修改既有 capability 的 requirement）

## Impact

- 新增依赖：`spring-ai-bom` 1.0.0、`spring-ai-starter-mcp-server-webmvc`（compile）、`spring-ai-starter-mcp-client`（test）。
- 新增代码：`mcp` 包（`EnviroInspectionMcpTools`）、`config` 包（`McpServerConfig`、`McpAuthInterceptor`）、视图 DTO（`OperationResultView`）、service 扩展（`EnviroInspectionQueryService`、`EnviroInspectionForwardService`）、client（`EnviroBrainForwardClient`）。
- 配置：`application.yml` 增加 `spring.ai.mcp.server` 与 `queqiao.mcp.auth`。
- 不影响 Phase 3 同步层既有契约；脑机端接入地址需改为 SSE 格式（`/mcp/sse`），详见实施文档。
