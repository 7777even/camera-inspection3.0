# Phase 4 本地手动冒烟步骤（鹊桥 MCP 封装层）

> 用途：Phase 4 交付后，在真机/本地环境对「鹊桥 → MCP Server」做一次端到端手动冒烟，为 Phase 5 脑机桌面端联调铺路。
> 自动化冒烟请直接跑 `mvn test`（见文末），本篇覆盖需要人工确认的网络/端点部分。

---

## 1. 前置条件

| 项 | 说明 |
| --- | --- |
| JDK | 17+（项目基线 Spring Boot 3.3.5） |
| 数据库 | `dev` 环境连 MySQL，需存在 `synced_inspection_record` / `synced_camera_result` / `synced_ledger_record` 三张表（由 Phase 3 同步层写入）。本地无 MySQL 时可临时改用 `test` profile（H2，见 §6）。 |
| 环保小脑 | 查询类工具不依赖环保小脑（直读鹊桥自有 `synced_*` 表）；操作类工具（`triggerInspection` / `downloadLedgerDocx`）会转发环保小脑，不可达时返回友好错误，**不会抛异常**。 |
| 端口 | 鹊桥服务默认 `8081`。 |

---

## 2. 启动服务

```bash
cd queqiao
# dev 环境（连 MySQL）
mvn spring-boot:run
# 或打 jar 后运行
mvn -o package -DskipTests
java -jar target/queqiao-sync-1.0.0-SNAPSHOT.jar
```

启动后日志应出现 MCP 工具注册信息（类似）：

```
Registered tools: 5
```

---

## 3. 确认 MCP 端点已映射（关键修正）

⚠️ **设计文档原写 `/mcp/enviro-inspection` + streamable-http，实现中已修订。**

实际解析依赖为 **Spring AI 1.0.0 + MCP Java SDK 0.10.0**，其 `spring-webmvc` 模块**仅支持 SSE 传输**（`WebMvcSseServerTransportProvider`），不支持 streamable-http 服务端传输。因此：

- `transport` 配置值为 `WEBMVC`（SSE），**不是** `streamable-http`。
- 在 SDK 0.10.0 中，SSE 端点以 functional `RouterFunction` 注册，路由路径 = `sse-endpoint` / `sse-message-endpoint` 本身，**`base-url` 不会前缀到路由上**。
- 最终暴露的端点为：
  - `GET  http://localhost:8081/mcp/sse`     —— SSE 流（建立会话、接收服务端消息）
  - `POST http://localhost:8081/mcp/message` —— JSON-RPC 消息通道（发送客户端请求，携带 `?sessionId=xxx`）

### 3.1 快速探活

SSE 流是长连接，用 `curl` 打开后应持续收到 `event: endpoint` 开头的事件（含 `sessionId`）：

```bash
curl -N -s http://localhost:8081/mcp/sse | head -5
# 期望看到类似：
# event: endpoint
# data: /mcp/message?sessionId=1a2b3c...
```

> 若 `curl` 立即返回 EOF 或连接被拒，说明服务未起/端点未映射，先查 §3 配置与启动日志。

### 3.2 用 mapping 端点核对（可选）

若开启了 `management.endpoints.web.exposure.include: mappings`，可访问 `http://localhost:8081/actuator/mappings` 确认存在 `mcp...RouterFunction` 相关路由（注意：SSE 路由为 functional `RouterFunction`，不会出现在 `@RequestMapping` 列表里，这是正常现象）。

---

## 4. 用 MCP SSE 客户端联调（推荐）

任意兼容 MCP 的 SSE 客户端均可。以下给出 **Python（modelcontextprotocol SDK）** 示例，验证「初始化 → 列出工具 → 调用查询工具」全链路。

```bash
pip install mcp
```

`smoke_client.py`：

```python
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession

async def main():
    url = "http://localhost:8081/mcp/sse"
    async with sse_client(url) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            tools = await session.list_tools()
            print("TOOLS:", [t.name for t in tools.tools])
            # 调用查询类工具（不依赖环保小脑）
            res = await session.call_tool(
                "getInspectionLedger",
                {"date": "2099-01-01", "status": None, "enterprise": None},
            )
            print("LEDGER:", res.content)

asyncio.run(main())
```

期望输出包含 5 个工具名：

```
getInspectionLedger, getCameraStatus, getInspectionSummary,
triggerInspection, downloadLedgerDocx
```

---

## 5. 工具清单与参数

| 工具名 | 作用 | 参数 | 返回 |
| --- | --- | --- | --- |
| `getInspectionLedger` | 取指定日期巡查台账（巡检汇总+摄像头结果+台账记录），直读鹊桥库 | `date:string(YYYY-MM-DD)`、`status:string?`、`enterprise:string?`（预留 no-op） | `InspectionLedgerView` |
| `getCameraStatus` | 摄像头状态：指定名返回最新快照+近 N 天历史；不指定返回每摄像头最新一条 | `cameraName:string?`、`historyDays:int?` | `CameraStatusView` |
| `getInspectionSummary` | 区间内巡检汇总：在线率、最差记录日、频繁离线摄像头排名 | `start:string(YYYY-MM-DD)`、`end:string(YYYY-MM-DD)` | `InspectionSummaryView` |
| `triggerInspection` | 触发环保小脑执行一次巡检（操作类，转发环保小脑） | `reason:string?` | `OperationResultView` |
| `downloadLedgerDocx` | 下载指定巡检记录的台账 Word 文档（操作类，转发环保小脑） | `inspectId:long` | `OperationResultView` |

> 操作类工具返回 `OperationResultView`（`{ ok:boolean, message:string, payload:object }`）；查询类返回各自视图 DTO。空数据场景查询工具会返回带 `message`（如「当日暂无同步数据」）的视图，不会报错。

---

## 6. 鉴权说明

- 默认 **关闭**：`queqiao.mcp.auth.enabled: false`，端点对所有人开放（依赖外部网关做 Bearer 校验）。
- 启用：设 `queqiao.mcp.auth.enabled: true` 并配置 `queqiao.mcp.auth.api-key`，客户端须在请求头带 `X-API-Key`。缺失/错误/空白配置均返回 `401`。
- 配置方式：`application.yml` 或环境变量 `QUEQIAO_MCP_API_KEY`。

---

## 7. 本地无 MySQL 时的 H2 冒烟（test profile）

```bash
cd queqiao
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

`test` profile 使用内存 H2（与 Phase 3 单测同一套 DDL），可验证端点与工具注册，但 `synced_*` 表为空，查询工具会返回「暂无同步数据」类视图，属预期。

---

## 8. 自动化冒烟（一次性全绿）

```bash
cd queqiao
mvn -o test          # 离线优先；若缺构件去掉 -o 在线解析
```

覆盖：
- Phase 3 既有同步层单测
- Phase 4 新增：Mapper 读方法、QueryService、ForwardService（含降级）、5 工具注册与端点映射（SSE RouterFunction）、MCP 鉴权拦截器
- 全量预期：BUILD SUCCESS，无 FAILURE / ERROR
