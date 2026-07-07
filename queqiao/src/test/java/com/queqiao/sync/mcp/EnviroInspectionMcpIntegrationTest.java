package com.queqiao.sync.mcp;

import com.queqiao.sync.AbstractQueqiaoTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EnviroInspectionMcpIntegrationTest extends AbstractQueqiaoTest {

    // 存在两个 ToolCallbackProvider：本项目注册的 enviroInspectionTools 与 MCP 自动配置的 mcpToolCallbacks。
    // 这里精确装配本项目提供的那个，断言 5 个工具被完整注册。
    @Autowired
    @Qualifier("enviroInspectionTools")
    ToolCallbackProvider toolCallbackProvider;

    @Test
    void fiveToolsRegistered() {
        ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
        List<String> names = Arrays.stream(callbacks)
                .map(c -> c.getToolDefinition().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                "getInspectionLedger", "getCameraStatus",
                "getInspectionSummary", "triggerInspection", "downloadLedgerDocx");
    }

    @Test
    void callGetInspectionLedger_returnsStructuredResult() {
        ToolCallback cb = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(c -> "getInspectionLedger".equals(c.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        // 远未来日期无同步数据 → 走 InspectionLedgerView.empty(date) 分支
        String args = "{\"date\":\"2099-01-01\",\"status\":null,\"enterprise\":null}";
        String resultJson = cb.call(args);
        assertThat(resultJson).contains("当日暂无同步数据");
    }
}
