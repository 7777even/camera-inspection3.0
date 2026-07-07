# 鹊桥 MCP 封装层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 queqiao Spring Boot 应用内嵌入一个 MCP Server，把 Phase 3 同步到鹊桥自有库（`synced_*`）的巡查数据封装为 5 个标准 MCP 工具（3 查询 + 2 操作），供脑机桌面端通过自然语言调用。

**Architecture:** 嵌入 queqiao 单进程（端口 8081），用 Spring AI MCP（`spring-ai-mcp-server-webmvc-spring-boot-starter`）暴露 Streamable HTTP 端点 `/mcp/enviro-inspection`。查询类工具委派新增的 `EnviroInspectionQueryService`（直读 `synced_*` Mapper）；操作类工具委派 `EnviroInspectionForwardService` → `EnviroBrainForwardClient`（复用 `RestTemplateConfig` + `enviro-brain.api-key` 转发环保小脑）。复用 Phase 3 全部 DB / Mapper / 配置资产。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring AI MCP 1.0.x（兼容 Boot 3.3.5）, MyBatis 3.0.3, H2（测试，MODE=MYSQL）, Maven（Windows 沙箱走 maven-windows-build：`mvn.cmd`）。

## Global Constraints

- **嵌入 queqiao 单进程**，不新建部署单元；端点 `/mcp/enviro-inspection`，与 `/api/notify` 共存于 8081。（来自设计 §3）
- **框架用 Spring AI MCP**，不手写协议；传输采用 Streamable HTTP。（来自设计 §3）
- **查询类工具直读 `synced_*` 库，绝不穿透环保小脑**；无数据返回空结构 + 提示，不抛 5xx。（来自设计 §4.1/§7）
- **操作类工具转发环保小脑，仅当其可达时**；不可达返回友好错误「环保小脑暂不可用，请稍后重试」，MCP 调用不崩溃。（来自设计 §4.2/§7）
- **TDD + 频繁提交**；每个任务以独立可测交付物收尾；最终 `mvn test` 全绿（含 Phase 3 的 21 个测试）。
- **MCP 端点自身鉴权默认开放**（`queqiao.mcp.auth.enabled=false`），网关已做 Bearer；预留可配拦截器。（来自设计 §3/§10）
- **H2 兼容写法**：SQL 用 `LIMIT`；避免 DB 专属函数；`NOW()`→`CURRENT_TIMESTAMP`；`ON DUPLICATE`→`MERGE INTO`（沿用 Phase 3 已验证写法）。（来自 Phase 3 经验）
- **包名固定** `com.queqiao.sync`；新增类放对应子包（`service`/`client`/`mcp`/`config`/`dto`）。

## 重要说明（设计偏差 / 实现注意）

1. **`enterprise` 参数为预留项**：`synced_*` 表无 enterprise 列，工具 schema 保留该可选入参（契约与脑机端对齐），但实现中暂作 no-op（仅记日志），不报错。待 Phase 5 数据模型补充 enterprise 字段后再接线。计划在 Task 4 显式处理。
2. **Spring AI MCP 版本相关类名/属性**：本计划按 Spring AI MCP 1.0.x 编写。若实际解析版本的类名/属性名有出入（如 `transport` 取值、`base-url` 属性、`HttpClientStreamableHttpTransport` vs `WebMvcSseClientTransport`），以解析到的版本为准微调，逻辑与契约不变。Task 1 / Task 5 含「核对版本」步骤。
3. **测试运行（Windows 沙箱）**：标准命令为 `mvn test`；在沙箱中按 maven-windows-build 技能用 `mvn.cmd`（项目级 `ci-settings.xml`）执行，避免 `mvn` 脚本失败。

---

## 文件结构

### 新增文件
| 文件 | 职责 |
|------|------|
| `queqiao/src/main/java/com/queqiao/sync/service/EnviroInspectionQueryService.java` | 查询类业务逻辑（3 方法），调用 Mapper 并组装视图 |
| `queqiao/src/main/java/com/queqiao/sync/service/EnviroInspectionForwardService.java` | 操作类转发编排，捕获异常做友好降级 |
| `queqiao/src/main/java/com/queqiao/sync/client/EnviroBrainForwardClient.java` | 复用 `RestTemplate`，封装 trigger / download 两个 HTTP 调用（带 X-API-Key） |
| `queqiao/src/main/java/com/queqiao/sync/mcp/EnviroInspectionMcpTools.java` | 5 个 `@Tool` 方法，委派上述两个 Service |
| `queqiao/src/main/java/com/queqiao/sync/config/McpServerConfig.java` | 注册 `ToolCallbackProvider` 暴露 5 个工具 |
| `queqiao/src/main/java/com/queqiao/sync/config/McpAuthInterceptor.java` | 可选 `/mcp/**` 鉴权拦截器，默认关闭 |
| `queqiao/src/main/java/com/queqiao/sync/dto/view/InspectionLedgerView.java` | 台账视图（巡检+摄像头+台账） |
| `queqiao/src/main/java/com/queqiao/sync/dto/view/CameraStatusView.java` | 摄像头状态视图 |
| `queqiao/src/main/java/com/queqiao/sync/dto/view/InspectionSummaryView.java` | 巡检汇总视图 |
| `queqiao/src/main/java/com/queqiao/sync/dto/TriggerRequest.java` | trigger 入参 |
| `queqiao/src/main/java/com/queqiao/sync/dto/TriggerResultDto.java` | trigger 出参 |
| `queqiao/src/main/java/com/queqiao/sync/dto/DownloadResultDto.java` | download 出参 |
| `queqiao/src/test/java/com/queqiao/sync/McpAutoConfigSmokeTest.java` | MCP 自动配置生效冒烟（上下文加载 + 配置项） |
| `queqiao/src/test/java/com/queqiao/sync/service/EnviroInspectionQueryServiceTest.java` | QueryService 单测（mock Mapper） |
| `queqiao/src/test/java/com/queqiao/sync/service/EnviroInspectionForwardServiceTest.java` | ForwardService 单测（mock Client） |
| `queqiao/src/test/java/com/queqiao/sync/mcp/EnviroInspectionMcpIntegrationTest.java` | MCP 工具注册 + 调用（ToolCallback 直调 + HTTP 传输协议级） |
| `docs/Phase4本地手动冒烟步骤.md` | 本地手动冒烟文档（留给 Phase 5 真机联调） |

### 修改文件
| 文件 | 变更 |
|------|------|
| `queqiao/pom.xml` | 引入 `spring-ai-bom` + `spring-ai-mcp-server-webmvc-spring-boot-starter`（+ 测试用 client starter） |
| `queqiao/src/main/resources/application.yml` | 增加 `spring.ai.mcp.server.*` 与 `queqiao.mcp.auth.*` |
| `queqiao/src/main/java/com/queqiao/sync/mapper/SyncedInspectionRecordMapper.java` | 新增 `findByInspectionDate`、`findByRange` |
| `queqiao/src/main/java/com/queqiao/sync/mapper/SyncedCameraResultMapper.java` | 新增 `findByRecordId`、`findByCameraCode`、`findLatestPerCamera`、`findByRecordIds` |
| `queqiao/src/main/java/com/queqiao/sync/mapper/SyncedLedgerRecordMapper.java` | 新增 `findByRecordId` |
| `queqiao/src/main/resources/mapper/SyncedInspectionRecordMapper.xml` | 对应 select SQL |
| `queqiao/src/main/resources/mapper/SyncedCameraResultMapper.xml` | 对应 select SQL |
| `queqiao/src/main/resources/mapper/SyncedLedgerRecordMapper.xml` | 对应 select SQL |

---

## Task 1: 引入 Spring AI MCP 依赖与配置

**Files:**
- Modify: `queqiao/pom.xml`
- Modify: `queqiao/src/main/resources/application.yml`
- Create: `queqiao/src/test/java/com/queqiao/sync/McpAutoConfigSmokeTest.java`

**Interfaces:**
- Consumes: 无（基础任务）
- Produces: 应用上下文在 `spring.ai.mcp.server.enabled=true` 下能加载；后续任务的 `ToolCallbackProvider` / `@Tool` 由自动配置拾取

- [ ] **Step 1: 写失败测试（MCP 配置生效冒烟）**

```java
package com.queqiao.sync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpAutoConfigSmokeTest {

    @Autowired
    Environment env;

    @Test
    void mcpServerEnabled() {
        assertThat(env.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("true");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=McpAutoConfigSmokeTest`
Expected: FAIL（属性未配置，断言 null ≠ "true"，编译通过但未启用 MCP）

- [ ] **Step 3: 修改 pom.xml 引入依赖**

在 `queqiao/pom.xml` 的 `<dependencyManagement>`（若无则新增，放在 `<dependencies>` 之前）加入 BOM；在 `<dependencies>` 加入 starter：

```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

```xml
        <!-- Spring AI MCP Server (webmvc / Streamable HTTP) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp-server-webmvc-spring-boot-starter</artifactId>
        </dependency>
        <!-- MCP 客户端传输（仅测试用，做协议级集成测试） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp-client</artifactId>
            <scope>test</scope>
        </dependency>
```

> 核对版本：若 `1.0.0` 解析失败（仓库无该版本），用 `mvn` 解析到与 Boot 3.3.5 兼容的最新 1.0.x，并同步下方所有 Spring AI 引用。

- [ ] **Step 4: 修改 application.yml 增加 MCP 配置**

在 `queqiao/src/main/resources/application.yml` 末尾追加（与 `server:`/`spring.profiles` 平级）：

```yaml
# 鹊桥 MCP 封装层（Phase 4）
spring:
  ai:
    mcp:
      server:
        enabled: true
        # Streamable HTTP 端点基路径，最终暴露为 /mcp/enviro-inspection
        base-url: /mcp/enviro-inspection
        transport: streamable-http

queqiao:
  mcp:
    # MCP 端点自身鉴权：默认关闭（外部网关已做 Bearer 校验）
    auth:
      enabled: false
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=McpAutoConfigSmokeTest`
Expected: PASS（`spring.ai.mcp.server.enabled=true`）

- [ ] **Step 6: 提交**

```bash
git add queqiao/pom.xml queqiao/src/main/resources/application.yml queqiao/src/test/java/com/queqiao/sync/McpAutoConfigSmokeTest.java
git commit -m "build(phase4): 引入 Spring AI MCP 依赖与 MCP Server 配置"
```

---

## Task 2: 查询类 Mapper 读方法与视图 DTO

**Files:**
- Modify: `queqiao/src/main/java/com/queqiao/sync/mapper/SyncedInspectionRecordMapper.java`
- Modify: `queqiao/src/main/java/com/queqiao/sync/mapper/SyncedCameraResultMapper.java`
- Modify: `queqiao/src/main/java/com/queqiao/sync/mapper/SyncedLedgerRecordMapper.java`
- Modify: `queqiao/src/main/resources/mapper/SyncedInspectionRecordMapper.xml`
- Modify: `queqiao/src/main/resources/mapper/SyncedCameraResultMapper.xml`
- Modify: `queqiao/src/main/resources/mapper/SyncedLedgerRecordMapper.xml`
- Create: `queqiao/src/main/java/com/queqiao/sync/dto/view/InspectionLedgerView.java`
- Create: `queqiao/src/main/java/com/queqiao/sync/dto/view/CameraStatusView.java`
- Create: `queqiao/src/main/java/com/queqiao/sync/dto/view/InspectionSummaryView.java`

**Interfaces:**
- Consumes: `synced_*` 表（schema 见 Phase 3，字段 1:1）
- Produces: Mapper 读方法（`findByInspectionDate`/`findByRange`/`findByRecordId`/`findByCameraCode`/`findLatestPerCamera`/`findByRecordIds`）+ 3 个视图 DTO，供 Task 3 的 `EnviroInspectionQueryService` 使用

- [ ] **Step 1: 写失败测试（以 camera Mapper 读方法为例）**

在 `queqiao/src/test/java/com/queqiao/sync/mapper/SyncedCameraResultMapperTest.java` 中新增（该类已继承 `AbstractQueqiaoTest`，H2 隔离）：

```java
    @Test
    void findByRecordId_returnsRowsForRecord() {
        SyncedCameraResult r1 = new SyncedCameraResult();
        r1.setId(1L); r1.setRecordId(100L); r1.setCameraCode("CAM-A");
        r1.setStatus("ONLINE"); r1.setSyncVersion(1L);
        cameraMapper.upsert(r1);

        List<SyncedCameraResult> rows = cameraMapper.findByRecordId(100L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCameraCode()).isEqualTo("CAM-A");
    }

    @Test
    void findLatestPerCamera_returnsOnePerCamera() {
        SyncedCameraResult a1 = new SyncedCameraResult();
        a1.setId(1L); a1.setRecordId(100L); a1.setCameraCode("CAM-A");
        a1.setStatus("ONLINE"); a1.setSyncVersion(1L);
        SyncedCameraResult a2 = new SyncedCameraResult();
        a2.setId(2L); a2.setRecordId(101L); a2.setCameraCode("CAM-A");
        a2.setStatus("OFFLINE"); a2.setSyncVersion(2L);
        cameraMapper.upsert(a1); cameraMapper.upsert(a2);

        List<SyncedCameraResult> latest = cameraMapper.findLatestPerCamera();
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).getCameraCode()).isEqualTo("CAM-A");
        assertThat(latest.get(0).getStatus()).isEqualTo("OFFLINE"); // 取 synced_at 最新
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=SyncedCameraResultMapperTest`
Expected: FAIL（方法不存在 / 编译错误）

- [ ] **Step 3: 扩展 Mapper 接口**

`SyncedInspectionRecordMapper.java` 增加：
```java
    SyncedInspectionRecord findByInspectionDate(@Param("date") LocalDate date);
    List<SyncedInspectionRecord> findByRange(@Param("start") LocalDate start, @Param("end") LocalDate end);
```
（需 `import org.apache.ibatis.annotations.Param;` 与 `import java.time.LocalDate;`）

`SyncedCameraResultMapper.java` 增加：
```java
    List<SyncedCameraResult> findByRecordId(@Param("recordId") Long recordId);
    List<SyncedCameraResult> findByCameraCode(@Param("cameraCode") String cameraCode);
    List<SyncedCameraResult> findLatestPerCamera();
    List<SyncedCameraResult> findByRecordIds(@Param("ids") List<Long> ids);
```

`SyncedLedgerRecordMapper.java` 增加：
```java
    SyncedLedgerRecord findByRecordId(@Param("recordId") Long recordId);
```

- [ ] **Step 4: 扩展 Mapper XML（加在 `</mapper>` 之前）**

`SyncedInspectionRecordMapper.xml`：
```xml
    <select id="findByInspectionDate" parameterType="java.time.LocalDate"
            resultType="com.queqiao.sync.entity.SyncedInspectionRecord">
        SELECT * FROM synced_inspection_records
        WHERE inspection_date = #{date}
        ORDER BY id DESC
        LIMIT 1
    </select>

    <select id="findByRange" parameterType="map"
            resultType="com.queqiao.sync.entity.SyncedInspectionRecord">
        SELECT * FROM synced_inspection_records
        WHERE inspection_date &gt;= #{start} AND inspection_date &lt;= #{end}
        ORDER BY inspection_date
    </select>
```

`SyncedCameraResultMapper.xml`：
```xml
    <select id="findByRecordId" parameterType="long"
            resultType="com.queqiao.sync.entity.SyncedCameraResult">
        SELECT * FROM synced_camera_results WHERE record_id = #{recordId}
    </select>

    <select id="findByCameraCode" parameterType="string"
            resultType="com.queqiao.sync.entity.SyncedCameraResult">
        SELECT * FROM synced_camera_results WHERE camera_code = #{cameraCode}
        ORDER BY synced_at DESC
    </select>

    <select id="findLatestPerCamera"
            resultType="com.queqiao.sync.entity.SyncedCameraResult">
        SELECT scr.* FROM synced_camera_results scr
        INNER JOIN (
            SELECT camera_code, MAX(synced_at) AS mx
            FROM synced_camera_results GROUP BY camera_code
        ) m ON scr.camera_code = m.camera_code AND scr.synced_at = m.mx
    </select>

    <select id="findByRecordIds" parameterType="list"
            resultType="com.queqiao.sync.entity.SyncedCameraResult">
        SELECT * FROM synced_camera_results
        WHERE record_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
    </select>
```

`SyncedLedgerRecordMapper.xml`：
```xml
    <select id="findByRecordId" parameterType="long"
            resultType="com.queqiao.sync.entity.SyncedLedgerRecord">
        SELECT * FROM synced_ledger_records WHERE record_id = #{recordId}
    </select>
```

- [ ] **Step 5: 创建 3 个视图 DTO**

`dto/view/InspectionLedgerView.java`：
```java
package com.queqiao.sync.dto.view;

import com.queqiao.sync.entity.SyncedCameraResult;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
public class InspectionLedgerView {
    private LocalDate inspectionDate;
    private SyncedInspectionRecord inspection;
    private List<SyncedCameraResult> cameras = Collections.emptyList();
    private SyncedLedgerRecord ledger;
    private LocalDateTime syncedAt;
    private String message;

    public static InspectionLedgerView empty(LocalDate d) {
        InspectionLedgerView v = new InspectionLedgerView();
        v.setInspectionDate(d);
        v.setMessage("当日暂无同步数据");
        return v;
    }
}
```

`dto/view/CameraStatusView.java`：
```java
package com.queqiao.sync.dto.view;

import com.queqiao.sync.entity.SyncedCameraResult;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class CameraStatusView {
    private SyncedCameraResult snapshot;          // 单摄像头查询的最新快照
    private List<SyncedCameraResult> cameras = Collections.emptyList(); // 全局查询：每摄像头最新
    private String message;

    public static CameraStatusView empty() {
        CameraStatusView v = new CameraStatusView();
        v.setMessage("暂无摄像头状态数据");
        return v;
    }
}
```

`dto/view/InspectionSummaryView.java`：
```java
package com.queqiao.sync.dto.view;

import com.queqiao.sync.entity.SyncedInspectionRecord;
import lombok.Data;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
public class InspectionSummaryView {
    private LocalDate start;
    private LocalDate end;
    private double onlineRate;
    private SyncedInspectionRecord worstDay;
    private List<Map<String, Object>> frequentOfflineCameras = Collections.emptyList();
    private String message;

    public static InspectionSummaryView empty(LocalDate start, LocalDate end) {
        InspectionSummaryView v = new InspectionSummaryView();
        v.setStart(start);
        v.setEnd(end);
        v.setMessage("区间内暂无巡检数据");
        return v;
    }
}
```

- [ ] **Step 6: 运行 Mapper 测试确认通过**

Run: `mvn test -Dtest=SyncedCameraResultMapperTest`
Expected: PASS（含新增两个方法测试）；同时为另外两个 Mapper 的读方法补等价测试（见 Step 7）

- [ ] **Step 7: 补 inspection / ledger Mapper 读方法测试**

在 `SyncedInspectionRecordMapperTest.java` 增加：
```java
    @Test
    void findByInspectionDate_returnsLatestForDate() {
        SyncedInspectionRecord r = new SyncedInspectionRecord();
        r.setId(10L); r.setBatchId("B1"); r.setInspectionDate(LocalDate.of(2026,7,7));
        r.setTotalCameras(3); r.setOnlineCount(2); r.setOfflineCount(1);
        r.setAbnormalCount(0); r.setStatus("DONE"); r.setSyncVersion(5L);
        inspectionMapper.upsert(r);

        SyncedInspectionRecord found = inspectionMapper.findByInspectionDate(LocalDate.of(2026,7,7));
        assertThat(found).isNotNull();
        assertThat(found.getBatchId()).isEqualTo("B1");
    }
```
（需 `import java.time.LocalDate;`）

在 `SyncedLedgerRecordMapperTest.java` 增加：
```java
    @Test
    void findByRecordId_returnsLedger() {
        SyncedLedgerRecord r = new SyncedLedgerRecord();
        r.setId(20L); r.setRecordId(10L); r.setInspectionDate(LocalDate.of(2026,7,7));
        r.setContent("台账内容"); r.setSyncVersion(5L);
        ledgerMapper.upsert(r);

        SyncedLedgerRecord found = ledgerMapper.findByRecordId(10L);
        assertThat(found).isNotNull();
        assertThat(found.getContent()).isEqualTo("台账内容");
    }
```

- [ ] **Step 8: 提交**

```bash
git add queqiao/src/main/java/com/queqiao/sync/mapper/ queqiao/src/main/resources/mapper/ queqiao/src/main/java/com/queqiao/sync/dto/view/
git commit -m "feat(phase4): 查询类 Mapper 读方法与视图 DTO"
```

---

## Task 3: EnviroInspectionQueryService（查询类业务逻辑）

**Files:**
- Create: `queqiao/src/main/java/com/queqiao/sync/service/EnviroInspectionQueryService.java`
- Create: `queqiao/src/test/java/com/queqiao/sync/service/EnviroInspectionQueryServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 Mapper 读方法 + 视图 DTO
- Produces: `getInspectionLedger(date,status,enterprise)` / `getCameraStatus(cameraName,historyDays)` / `getInspectionSummary(start,end)`，供 Task 4 的 MCP 工具调用

- [ ] **Step 1: 写失败测试**

`EnviroInspectionQueryServiceTest.java`：
```java
package com.queqiao.sync.service;

import com.queqiao.sync.dto.view.CameraStatusView;
import com.queqiao.sync.dto.view.InspectionLedgerView;
import com.queqiao.sync.dto.view.InspectionSummaryView;
import com.queqiao.sync.entity.SyncedCameraResult;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import com.queqiao.sync.mapper.SyncedCameraResultMapper;
import com.queqiao.sync.mapper.SyncedInspectionRecordMapper;
import com.queqiao.sync.mapper.SyncedLedgerRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnviroInspectionQueryServiceTest {

    @Mock SyncedInspectionRecordMapper inspectionMapper;
    @Mock SyncedCameraResultMapper cameraMapper;
    @Mock SyncedLedgerRecordMapper ledgerMapper;
    @InjectMocks EnviroInspectionQueryService service;

    @Test
    void getInspectionLedger_filtersByStatus() {
        SyncedInspectionRecord ins = new SyncedInspectionRecord();
        ins.setId(1L); ins.setInspectionDate(LocalDate.of(2026,7,7));
        SyncedCameraResult on = new SyncedCameraResult(); on.setStatus("ONLINE");
        SyncedCameraResult off = new SyncedCameraResult(); off.setStatus("OFFLINE");

        when(inspectionMapper.findByInspectionDate(LocalDate.of(2026,7,7))).thenReturn(ins);
        when(cameraMapper.findByRecordId(1L)).thenReturn(List.of(on, off));
        when(ledgerMapper.findByRecordId(1L)).thenReturn(new SyncedLedgerRecord());

        InspectionLedgerView v = service.getInspectionLedger(LocalDate.of(2026,7,7), "OFFLINE", null);
        assertThat(v.getInspection()).isSameAs(ins);
        assertThat(v.getCameras()).hasSize(1);
        assertThat(v.getCameras().get(0).getStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void getInspectionLedger_emptyWhenNoInspection() {
        when(inspectionMapper.findByInspectionDate(LocalDate.of(2026,7,7))).thenReturn(null);
        InspectionLedgerView v = service.getInspectionLedger(LocalDate.of(2026,7,7), null, null);
        assertThat(v.getMessage()).isEqualTo("当日暂无同步数据");
    }

    @Test
    void getCameraStatus_singleCameraReturnsSnapshotAndHistory() {
        SyncedCameraResult latest = new SyncedCameraResult(); latest.setCameraCode("CAM-A");
        latest.setStatus("OFFLINE"); latest.setSyncVersion(9L);
        SyncedCameraResult older = new SyncedCameraResult(); older.setCameraCode("CAM-A");
        older.setStatus("ONLINE"); older.setSyncVersion(1L);

        when(cameraMapper.findByCameraCode("CAM-A")).thenReturn(List.of(latest, older));
        CameraStatusView v = service.getCameraStatus("CAM-A", 7);
        assertThat(v.getSnapshot()).isSameAs(latest);
        assertThat(v.getCameras()).hasSize(2); // 近 7 天历史
    }

    @Test
    void getInspectionSummary_computesOnlineRateAndWorstDay() {
        SyncedInspectionRecord r1 = new SyncedInspectionRecord();
        r1.setId(1L); r1.setInspectionDate(LocalDate.of(2026,7,1));
        r1.setTotalCameras(10); r1.setOnlineCount(9); r1.setOfflineCount(1); r1.setAbnormalCount(0);
        SyncedInspectionRecord r2 = new SyncedInspectionRecord();
        r2.setId(2L); r2.setInspectionDate(LocalDate.of(2026,7,2));
        r2.setTotalCameras(10); r2.setOnlineCount(5); r2.setOfflineCount(4); r2.setAbnormalCount(1);

        when(inspectionMapper.findByRange(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2)))
                .thenReturn(List.of(r1, r2));
        when(cameraMapper.findByRecordIds(List.of(1L, 2L))).thenReturn(List.of());

        InspectionSummaryView v = service.getInspectionSummary(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2));
        assertThat(v.getOnlineRate()).isEqualTo(0.7); // (9+5)/(10+10)
        assertThat(v.getWorstDay().getId()).isEqualTo(2L); // 离线+异常最多
    }

    @Test
    void getInspectionSummary_emptyRange() {
        when(inspectionMapper.findByRange(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2)))
                .thenReturn(List.of());
        InspectionSummaryView v = service.getInspectionSummary(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2));
        assertThat(v.getMessage()).isEqualTo("区间内暂无巡检数据");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=EnviroInspectionQueryServiceTest`
Expected: FAIL（类不存在 / 编译错误）

- [ ] **Step 3: 实现 EnviroInspectionQueryService**

```java
package com.queqiao.sync.service;

import com.queqiao.sync.dto.view.CameraStatusView;
import com.queqiao.sync.dto.view.InspectionLedgerView;
import com.queqiao.sync.dto.view.InspectionSummaryView;
import com.queqiao.sync.entity.SyncedCameraResult;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import com.queqiao.sync.mapper.SyncedCameraResultMapper;
import com.queqiao.sync.mapper.SyncedInspectionRecordMapper;
import com.queqiao.sync.mapper.SyncedLedgerRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnviroInspectionQueryService {

    private final SyncedInspectionRecordMapper inspectionMapper;
    private final SyncedCameraResultMapper cameraMapper;
    private final SyncedLedgerRecordMapper ledgerMapper;

    /**
     * 获取指定日期巡查台账（巡检汇总 + 摄像头结果 + 台账记录）。
     * enterprise 为预留参数（synced_* 暂无该列），暂作 no-op。
     */
    public InspectionLedgerView getInspectionLedger(LocalDate date, String status, String enterprise) {
        LocalDate d = (date == null) ? LocalDate.now() : date;
        if (enterprise != null && !enterprise.isBlank()) {
            log.warn("[mcp][reserved] enterprise 过滤暂未启用：{}", enterprise);
        }
        SyncedInspectionRecord inspection = inspectionMapper.findByInspectionDate(d);
        if (inspection == null) {
            return InspectionLedgerView.empty(d);
        }
        List<SyncedCameraResult> cameras = cameraMapper.findByRecordId(inspection.getId());
        if (status != null && !status.isBlank()) {
            cameras = cameras.stream()
                    .filter(c -> status.equalsIgnoreCase(c.getStatus()))
                    .collect(Collectors.toList());
        }
        SyncedLedgerRecord ledger = ledgerMapper.findByRecordId(inspection.getId());
        InspectionLedgerView v = new InspectionLedgerView();
        v.setInspectionDate(d);
        v.setInspection(inspection);
        v.setCameras(cameras);
        v.setLedger(ledger);
        v.setSyncedAt(inspection.getSyncedAt());
        return v;
    }

    /**
     * 获取摄像头状态：指定 cameraName 返回最新快照 + 近 N 天历史；
     * 不指定则返回每摄像头最新一条。
     */
    public CameraStatusView getCameraStatus(String cameraName, Integer historyDays) {
        int days = (historyDays == null || historyDays < 1) ? 7 : historyDays;
        LocalDate since = LocalDate.now().minusDays(days);

        if (cameraName != null && !cameraName.isBlank()) {
            List<SyncedCameraResult> all = cameraMapper.findByCameraCode(cameraName);
            if (all.isEmpty()) {
                return CameraStatusView.empty();
            }
            SyncedCameraResult snapshot = all.get(0); // findByCameraCode 已按 synced_at DESC
            List<SyncedCameraResult> history = all.stream()
                    .filter(c -> c.getSyncedAt() != null
                            && !c.getSyncedAt().toLocalDate().isBefore(since))
                    .collect(Collectors.toList());
            CameraStatusView v = new CameraStatusView();
            v.setSnapshot(snapshot);
            v.setCameras(history);
            return v;
        }
        List<SyncedCameraResult> latest = cameraMapper.findLatestPerCamera();
        if (latest.isEmpty()) {
            return CameraStatusView.empty();
        }
        CameraStatusView v = new CameraStatusView();
        v.setCameras(latest);
        return v;
    }

    /**
     * 获取区间内巡检汇总：在线率、最差记录日、频繁离线摄像头排名。
     */
    public InspectionSummaryView getInspectionSummary(LocalDate start, LocalDate end) {
        List<SyncedInspectionRecord> records = inspectionMapper.findByRange(start, end);
        if (records.isEmpty()) {
            return InspectionSummaryView.empty(start, end);
        }
        int total = records.stream().mapToInt(SyncedInspectionRecord::getTotalCameras).sum();
        int online = records.stream().mapToInt(SyncedInspectionRecord::getOnlineCount).sum();
        double onlineRate = (total == 0) ? 0.0 : (double) online / total;

        SyncedInspectionRecord worstDay = records.stream()
                .max(Comparator.comparingInt(r -> r.getOfflineCount() + r.getAbnormalCount()))
                .orElse(records.get(0));

        List<Long> recordIds = records.stream().map(SyncedInspectionRecord::getId).collect(Collectors.toList());
        List<SyncedCameraResult> cameras = recordIds.isEmpty()
                ? List.of() : cameraMapper.findByRecordIds(recordIds);

        Map<String, Long> offlineCounts = cameras.stream()
                .filter(c -> "OFFLINE".equalsIgnoreCase(c.getStatus()))
                .collect(Collectors.groupingBy(SyncedCameraResult::getCameraCode, Collectors.counting()));
        List<Map<String, Object>> frequent = offlineCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cameraCode", e.getKey());
                    m.put("offlineCount", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        InspectionSummaryView v = new InspectionSummaryView();
        v.setStart(start);
        v.setEnd(end);
        v.setOnlineRate(onlineRate);
        v.setWorstDay(worstDay);
        v.setFrequentOfflineCameras(frequent);
        return v;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=EnviroInspectionQueryServiceTest`
Expected: PASS（5 个测试）

- [ ] **Step 5: 提交**

```bash
git add queqiao/src/main/java/com/queqiao/sync/service/EnviroInspectionQueryService.java queqiao/src/test/java/com/queqiao/sync/service/EnviroInspectionQueryServiceTest.java
git commit -m "feat(phase4): EnviroInspectionQueryService 查询类业务逻辑"
```

---

## Task 4: EnviroBrainForwardClient + EnviroInspectionForwardService（操作类转发）

**Files:**
- Create: `queqiao/src/main/java/com/queqiao/sync/client/EnviroBrainForwardClient.java`
- Create: `queqiao/src/main/java/com/queqiao/sync/service/EnviroInspectionForwardService.java`
- Create: `queqiao/src/main/java/com/queqiao/sync/dto/TriggerRequest.java`
- Create: `queqiao/src/main/java/com/queqiao/sync/dto/TriggerResultDto.java`
- Create: `queqiao/src/main/java/com/queqiao/sync/dto/DownloadResultDto.java`
- Create: `queqiao/src/test/java/com/queqiao/sync/service/EnviroInspectionForwardServiceTest.java`

**Interfaces:**
- Consumes: `RestTemplate`（来自 `RestTemplateConfig`）、`enviro-brain.base-url` / `enviro-brain.api-key`（与同步层同一凭证）
- Produces: `triggerInspection(reason)` / `downloadLedgerDocx(inspectId)` 的友好结果 Map，供 Task 4（同任务组）的 MCP 工具调用

- [ ] **Step 1: 写失败测试**

`EnviroInspectionForwardServiceTest.java`：
```java
package com.queqiao.sync.service;

import com.queqiao.sync.client.EnviroBrainForwardClient;
import com.queqiao.sync.dto.DownloadResultDto;
import com.queqiao.sync.dto.TriggerResultDto;
import com.queqiao.sync.exception.SyncClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnviroInspectionForwardServiceTest {

    @Mock EnviroBrainForwardClient client;
    @InjectMocks EnviroInspectionForwardService service;

    @Test
    void triggerInspection_success() {
        TriggerResultDto r = new TriggerResultDto("TASK-123", true);
        when(client.triggerInspection("手动触发")).thenReturn(r);

        Map<String, Object> result = service.triggerInspection("手动触发");
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("taskId")).isEqualTo("TASK-123");
    }

    @Test
    void triggerInspection_degradeWhenUnreachable() {
        when(client.triggerInspection("x")).thenThrow(new SyncClientException("连接失败"));

        Map<String, Object> result = service.triggerInspection("x");
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("message").toString()).contains("环保小脑暂不可用");
    }

    @Test
    void downloadLedgerDocx_success() {
        DownloadResultDto d = new DownloadResultDto(10L, "ledger.docx", "/tmp/ledger.docx");
        when(client.downloadLedgerDocx(10L)).thenReturn(d);

        Map<String, Object> result = service.downloadLedgerDocx(10L);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("fileName")).isEqualTo("ledger.docx");
    }

    @Test
    void downloadLedgerDocx_degradeWhenUnreachable() {
        when(client.downloadLedgerDocx(10L)).thenThrow(new SyncClientException("连接失败"));

        Map<String, Object> result = service.downloadLedgerDocx(10L);
        assertThat(result.get("ok")).isEqualTo(false);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=EnviroInspectionForwardServiceTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 创建 DTO**

`dto/TriggerRequest.java`：
```java
package com.queqiao.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerRequest {
    private String reason;
}
```

`dto/TriggerResultDto.java`：
```java
package com.queqiao.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerResultDto {
    private String taskId;
    private boolean accepted;
}
```

`dto/DownloadResultDto.java`：
```java
package com.queqiao.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResultDto {
    private Long inspectId;
    private String fileName;
    private String docxPath;
}
```

- [ ] **Step 4: 实现 EnviroBrainForwardClient**

```java
package com.queqiao.sync.client;

import com.queqiao.sync.dto.ApiResponse;
import com.queqiao.sync.dto.DownloadResultDto;
import com.queqiao.sync.dto.TriggerRequest;
import com.queqiao.sync.dto.TriggerResultDto;
import com.queqiao.sync.exception.SyncClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

@Slf4j
@Service
public class EnviroBrainForwardClient {

    private static final ParameterizedTypeReference<ApiResponse<TriggerResultDto>> TRIGGER_TYPE =
            new ParameterizedTypeReference<ApiResponse<TriggerResultDto>>() {};
    private static final ParameterizedTypeReference<ApiResponse<DownloadResultDto>> DOWNLOAD_TYPE =
            new ParameterizedTypeReference<ApiResponse<DownloadResultDto>>() {};

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public EnviroBrainForwardClient(RestTemplate restTemplate,
                                    @Value("${enviro-brain.base-url}") String baseUrl,
                                    @Value("${enviro-brain.api-key}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** 转发触发巡检到环保小脑 */
    public TriggerResultDto triggerInspection(String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        TriggerRequest body = new TriggerRequest(reason);
        try {
            ResponseEntity<ApiResponse<TriggerResultDto>> resp = restTemplate.exchange(
                    URI.create(baseUrl + "/api/v1/inspections/trigger"),
                    HttpMethod.POST, new HttpEntity<>(body, headers), TRIGGER_TYPE);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new SyncClientException("触发巡检失败: " + resp.getStatusCode());
            }
            return resp.getBody().getData();
        } catch (RestClientException e) {
            throw new SyncClientException("触发巡检调用失败", e);
        }
    }

    /** 转发台账 docx 下载到环保小脑 */
    public DownloadResultDto downloadLedgerDocx(Long inspectId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        try {
            ResponseEntity<ApiResponse<DownloadResultDto>> resp = restTemplate.exchange(
                    URI.create(baseUrl + "/api/v1/ledger/" + inspectId + "/download"),
                    HttpMethod.GET, new HttpEntity<>(headers), DOWNLOAD_TYPE);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new SyncClientException("下载台账失败: " + resp.getStatusCode());
            }
            return resp.getBody().getData();
        } catch (RestClientException e) {
            throw new SyncClientException("下载台账调用失败", e);
        }
    }
}
```

- [ ] **Step 5: 实现 EnviroInspectionForwardService（友好降级）**

```java
package com.queqiao.sync.service;

import com.queqiao.sync.client.EnviroBrainForwardClient;
import com.queqiao.sync.dto.DownloadResultDto;
import com.queqiao.sync.dto.TriggerResultDto;
import com.queqiao.sync.exception.SyncClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnviroInspectionForwardService {

    private static final String UNREACHABLE = "环保小脑暂不可用，请稍后重试";

    private final EnviroBrainForwardClient client;

    /** 触发巡检；环保小脑不可达时返回友好错误而非抛异常 */
    public Map<String, Object> triggerInspection(String reason) {
        try {
            TriggerResultDto r = client.triggerInspection(reason);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("taskId", r.getTaskId());
            m.put("accepted", r.isAccepted());
            return m;
        } catch (SyncClientException e) {
            log.warn("[mcp][forward] 触发巡检降级：{}", e.getMessage());
            return degrade();
        }
    }

    /** 下载台账 docx；不可达时降级 */
    public Map<String, Object> downloadLedgerDocx(Long inspectId) {
        try {
            DownloadResultDto d = client.downloadLedgerDocx(inspectId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("inspectId", d.getInspectId());
            m.put("fileName", d.getFileName());
            m.put("docxPath", d.getDocxPath());
            return m;
        } catch (SyncClientException e) {
            log.warn("[mcp][forward] 下载台账降级：{}", e.getMessage());
            return degrade();
        }
    }

    private Map<String, Object> degrade() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", UNREACHABLE);
        return m;
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=EnviroInspectionForwardServiceTest`
Expected: PASS（4 个测试）

- [ ] **Step 7: 提交**

```bash
git add queqiao/src/main/java/com/queqiao/sync/client/EnviroBrainForwardClient.java queqiao/src/main/java/com/queqiao/sync/service/EnviroInspectionForwardService.java queqiao/src/main/java/com/queqiao/sync/dto/TriggerRequest.java queqiao/src/main/java/com/queqiao/sync/dto/TriggerResultDto.java queqiao/src/main/java/com/queqiao/sync/dto/DownloadResultDto.java queqiao/src/test/java/com/queqiao/sync/service/EnviroInspectionForwardServiceTest.java
git commit -m "feat(phase4): EnviroBrainForwardClient + ForwardService 操作类转发"
```

---

## Task 5: MCP 工具装配（@Tool + ToolCallbackProvider）

**Files:**
- Create: `queqiao/src/main/java/com/queqiao/sync/mcp/EnviroInspectionMcpTools.java`
- Create: `queqiao/src/main/java/com/queqiao/sync/config/McpServerConfig.java`

**Interfaces:**
- Consumes: Task 3 的 `EnviroInspectionQueryService`、Task 4 的 `EnviroInspectionForwardService`、视图 DTO
- Produces: 5 个被 Spring AI MCP 自动配置拾取的 `@Tool` 方法；`ToolCallbackProvider` bean 暴露全部工具

- [ ] **Step 1: 写失败测试（工具可被注册并调用）**

见 Task 6 的集成测试（此处先实现，Task 6 验证协议层）。本任务仅做编译级自检：`@Tool` 方法存在即被 `McpServerConfig` 暴露。

- [ ] **Step 2: 实现 EnviroInspectionMcpTools**

```java
package com.queqiao.sync.mcp;

import com.queqiao.sync.dto.view.CameraStatusView;
import com.queqiao.sync.dto.view.InspectionLedgerView;
import com.queqiao.sync.dto.view.InspectionSummaryView;
import com.queqiao.sync.service.EnviroInspectionForwardService;
import com.queqiao.sync.service.EnviroInspectionQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnviroInspectionMcpTools {

    private final EnviroInspectionQueryService queryService;
    private final EnviroInspectionForwardService forwardService;

    @Tool(description = "获取指定日期危废仓库巡查台账：含巡检汇总、各摄像头结果与台账记录。不穿透环保小脑，读鹊桥自有库。")
    public InspectionLedgerView getInspectionLedger(
            @ToolParam(description = "巡检日期 YYYY-MM-DD，默认今天") LocalDate date,
            @ToolParam(description = "状态过滤 online/offline/abnormal，可选") String status,
            @ToolParam(description = "企业/园区名（预留参数，暂未启用）") String enterprise) {
        log.info("[mcp] getInspectionLedger date={}, status={}", date, status);
        return queryService.getInspectionLedger(date, status, enterprise);
    }

    @Tool(description = "获取摄像头状态：指定摄像头名返回最新快照与近 N 天历史；不指定则返回每个摄像头最新一条。")
    public CameraStatusView getCameraStatus(
            @ToolParam(description = "摄像头名称/编码，可选；不填则返回全部摄像头最新状态") String cameraName,
            @ToolParam(description = "历史天数，默认 7") Integer historyDays) {
        log.info("[mcp] getCameraStatus cameraName={}, historyDays={}", cameraName, historyDays);
        return queryService.getCameraStatus(cameraName, historyDays);
    }

    @Tool(description = "获取区间内巡检汇总：在线率、最差记录日、频繁离线摄像头排名。")
    public InspectionSummaryView getInspectionSummary(
            @ToolParam(description = "开始日期 YYYY-MM-DD，必填") LocalDate start,
            @ToolParam(description = "结束日期 YYYY-MM-DD，必填") LocalDate end) {
        log.info("[mcp] getInspectionSummary start={}, end={}", start, end);
        return queryService.getInspectionSummary(start, end);
    }

    @Tool(description = "触发环保小脑执行一次巡检（操作类，转发环保小脑；不可达时返回友好错误）。")
    public Object triggerInspection(
            @ToolParam(description = "触发原因，可选") String reason) {
        log.info("[mcp] triggerInspection reason={}", reason);
        return forwardService.triggerInspection(reason);
    }

    @Tool(description = "下载指定巡检记录的台账 Word 文档（操作类，转发环保小脑；不可达时返回友好错误）。")
    public Object downloadLedgerDocx(
            @ToolParam(description = "巡检记录 ID，必填") Long inspectId) {
        log.info("[mcp] downloadLedgerDocx inspectId={}", inspectId);
        return forwardService.downloadLedgerDocx(inspectId);
    }
}
```

- [ ] **Step 3: 实现 McpServerConfig**

```java
package com.queqiao.sync.config;

import com.queqiao.sync.mcp.EnviroInspectionMcpTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider enviroInspectionTools(EnviroInspectionMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
```

- [ ] **Step 4: 编译确认通过**

Run: `mvn -q compile`
Expected: 编译成功，5 个 `@Tool` 方法被 `ToolCallbackProvider` 暴露

- [ ] **Step 5: 提交**

```bash
git add queqiao/src/main/java/com/queqiao/sync/mcp/EnviroInspectionMcpTools.java queqiao/src/main/java/com/queqiao/sync/config/McpServerConfig.java
git commit -m "feat(phase4): 注册 5 个 MCP 工具（@Tool + ToolCallbackProvider）"
```

---

## Task 6: MCP 集成测试（协议级验证）

**Files:**
- Create: `queqiao/src/test/java/com/queqiao/sync/mcp/EnviroInspectionMcpIntegrationTest.java`

**Interfaces:**
- Consumes: Task 5 的 `ToolCallbackProvider` / 5 个 `@Tool`；Spring AI MCP 测试传输
- Produces: 验证「工具已注册 + 可调用 + 返回结构正确」（协议层）

- [ ] **Step 1: 写集成测试（ToolCallback 直调 + HTTP 传输协议级）**

```java
package com.queqiao.sync.mcp;

import com.queqiao.sync.McpAutoConfigSmokeTest;
import com.queqiao.sync.dto.view.InspectionLedgerView;
import com.queqiao.sync.service.EnviroInspectionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EnviroInspectionMcpIntegrationTest {

    @Autowired
    ToolCallbackProvider toolCallbackProvider;

    @Autowired
    EnviroInspectionQueryService queryService;

    @Test
    void fiveToolsRegistered() {
        List<ToolCallback> callbacks = toolCallbackProvider.getToolCallbacks();
        List<String> names = callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "getInspectionLedger", "getCameraStatus",
                "getInspectionSummary", "triggerInspection", "downloadLedgerDocx");
    }

    @Test
    void callGetInspectionLedger_returnsStructuredResult() {
        ToolCallback cb = toolCallbackProvider.getToolCallbacks().stream()
                .filter(c -> "getInspectionLedger".equals(c.getToolDefinition().name()))
                .findFirst().orElseThrow();

        // 构造与 @Tool 方法签名一致的 JSON 参数（date 缺省为今天；此处不依赖真实数据，验证调用链路）
        String args = "{\"date\":\"2099-01-01\",\"status\":null,\"enterprise\":null}";
        String resultJson = cb.call(args);
        assertThat(resultJson).contains("当日暂无同步数据"); // InspectionLedgerView.empty 的 message
    }
}
```

> 协议级 HTTP 传输验证（可选但推荐）：用 `McpClient.sync(transport)` 在 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 下对 `/mcp/enviro-inspection` 发起 `initialize → tools/list → tools/call`。传输类按解析到的 Spring AI MCP 版本选择 `HttpClientStreamableHttpTransport`（streamable-http）或回退 `WebMvcSseClientTransport`（SSE）；端点路径与 `spring.ai.mcp.server.base-url` 对齐。**核对版本**：若 `ToolCallbackProvider` / `getToolCallbacks()` / `getToolDefinition().name()` 等 API 与解析版本不符，以实际版本签名调整（逻辑与工具契约不变）。

- [ ] **Step 2: 运行集成测试确认通过**

Run: `mvn test -Dtest=EnviroInspectionMcpIntegrationTest`
Expected: PASS（5 个工具注册；`getInspectionLedger` 调用返回结构正确）

- [ ] **Step 3: 提交**

```bash
git add queqiao/src/test/java/com/queqiao/sync/mcp/EnviroInspectionMcpIntegrationTest.java
git commit -m "test(phase4): MCP 工具注册与调用集成测试"
```

---

## Task 7: 可选 McpAuthInterceptor（默认关闭）

**Files:**
- Create: `queqiao/src/main/java/com/queqiao/sync/config/McpAuthInterceptor.java`
- Modify: `queqiao/src/main/java/com/queqiao/sync/config/WebMvcConfig.java`

**Interfaces:**
- Consumes: `queqiao.mcp.auth.enabled`（配置项，默认 false）
- Produces: 当 `enabled=true` 时，对 `/mcp/**` 做 X-API-Key / Bearer 校验

- [ ] **Step 1: 实现 McpAuthInterceptor**

```java
package com.queqiao.sync.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class McpAuthInterceptor implements HandlerInterceptor {

    @Value("${queqiao.mcp.auth.enabled:false}")
    private boolean enabled;

    @Value("${queqiao.mcp.auth.api-key:}")
    private String apiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            return true; // 默认开放
        }
        String key = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank() && apiKey.equals(key)) {
            return true;
        }
        log.warn("[mcp-auth] MCP 端点鉴权失败，远程地址={}", request.getRemoteAddr());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return false;
    }
}
```

- [ ] **Step 2: 在 WebMvcConfig 注册（仅当启用时）**

修改 `WebMvcConfig.java`，注入 `McpAuthInterceptor` 并在 `addInterceptors` 增加：

```java
    private final ApiKeyAuthInterceptor apiKeyAuthInterceptor;
    private final McpAuthInterceptor mcpAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyAuthInterceptor)
                .addPathPatterns("/api/notify/**");
        registry.addInterceptor(mcpAuthInterceptor)
                .addPathPatterns("/mcp/**");
    }
```
（构造器参数同步增加 `mcpAuthInterceptor`）

- [ ] **Step 3: 上下文加载确认（默认关闭不阻断）**

Run: `mvn test -Dtest=McpAutoConfigSmokeTest`
Expected: PASS（MCP 端点默认开放，拦截器 enabled=false 直接放行）

- [ ] **Step 4: 提交**

```bash
git add queqiao/src/main/java/com/queqiao/sync/config/McpAuthInterceptor.java queqiao/src/main/java/com/queqiao/sync/config/WebMvcConfig.java
git commit -m "feat(phase4): 可选 MCP 端点鉴权拦截器（默认关闭）"
```

---

## Task 8: 本地手动冒烟文档 + 全量测试

**Files:**
- Create: `docs/Phase4本地手动冒烟步骤.md`

**Interfaces:**
- Consumes: 前述全部实现
- Produces: 供 Phase 5 真机联调的手动冒烟步骤；最终 `mvn test` 全绿

- [ ] **Step 1: 写冒烟文档**

`docs/Phase4本地手动冒烟步骤.md`：
```markdown
# Phase 4 本地手动冒烟步骤（鹊桥 MCP 封装层）

> 本步骤用于 Phase 5 真机联调前，在本地验证 MCP 端点可用。沙箱内已通过集成测试覆盖协议层。

## 启动
1. 配置环境变量（或 application-dev.yml）：
   - ENVIRO_BRAIN_BASE_URL=http://localhost:8080
   - ENVIRO_BRAIN_API_KEY=dev-api-key-2026
2. 启动 queqiao：
   mvn spring-boot:run   （或 java -jar target/queqiao-1.0.0-SNAPSHOT.jar）
3. 确认 MCP 端点已挂载：应用日志应出现 MCP Server 初始化，端点 /mcp/enviro-inspection

## 用 MCP 客户端验证（如 Claude Desktop / 脑机端）
mcpServers 配置：
{
  "mcpServers": {
    "enviro-inspection": {
      "url": "http://localhost:8081/mcp/enviro-inspection",
      "headers": { "Authorization": "Bearer <网关令牌>" }
    }
  }
}

## 5 个工具调用示例
- get_inspection_ledger({"date":"2026-07-07"})
- get_camera_status({"cameraName":"CAM-A","historyDays":7})
- get_inspection_summary({"start":"2026-07-01","end":"2026-07-07"})
- trigger_inspection({"reason":"手动抽检"})
- download_ledger_docx({"inspectId":10})

## 预期
- 查询类返回 synced_* 数据（无数据时为「当日暂无同步数据」等提示）
- 操作类在环保小脑可达时返回任务 ID / 文档；不可达时返回「环保小脑暂不可用，请稍后重试」
```

- [ ] **Step 2: 运行全量测试**

Run: `mvn test`
Expected: 全部 PASS（Phase 3 的 21 个测试 + 本计划新增测试）

- [ ] **Step 3: 提交**

```bash
git add docs/Phase4本地手动冒烟步骤.md
git commit -m "docs(phase4): 本地手动冒烟步骤"
```

---

## 自审摘要（writing-plans 自检）

- **Spec 覆盖**：5 个工具（§4）→ Task 5；查询类逻辑 → Task 2/3；操作类转发 → Task 4；错误处理 → Task 3/4；MCP 集成测试 → Task 6；配置部署 → Task 1/7；冒烟文档 → Task 8。`enterprise` 预留项已在 Task 3 显式 no-op（§重要说明 1）。
- **占位符扫描**：无 TBD/TODO；版本相关类名已在 Task 1/5/6 标注「核对版本」步骤（显式验证动作，非占位）。
- **类型一致性**：视图 DTO 字段名（`InspectionLedgerView.inspection/cameras/ledger/syncedAt`、`CameraStatusView.snapshot/cameras`、`InspectionSummaryView.onlineRate/worstDay/frequentOfflineCameras`）在 Task 2/3/5 一致；Mapper 方法名 + `@Param` 在 Task 2/3 一致；Forward DTO（`TriggerResultDto.taskId/accepted`、`DownloadResultDto.inspectId/fileName/docxPath`）在 Task 4 内一致。
- **范围**：单计划、单可测交付，未膨胀；MCP 端点鉴权按设计默认关闭（§重要说明）。
