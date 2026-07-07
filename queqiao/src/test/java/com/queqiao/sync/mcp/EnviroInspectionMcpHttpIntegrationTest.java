package com.queqiao.sync.mcp;

import com.queqiao.sync.AbstractQueqiaoTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnviroInspectionMcpHttpIntegrationTest extends AbstractQueqiaoTest {

    @Autowired
    RouterFunctionMapping routerFunctionMapping;

    /**
     * 端点映射验证（闭合 Task 1/5 的 open Minor）。
     * 实际解析依赖栈为 Spring AI 1.0.0 + MCP Java SDK 0.10.0；该 SDK 的 spring-webmvc 模块
     * 仅提供 SSE 传输（WebMvcSseServerTransportProvider），并不含 streamable-http 服务端传输，
     * 因此 application.yml 的 transport 使用 WEBMVC(SSE)。SSE 端点以 functional RouterFunction
     * 形式注册（不出现在 RequestMappingHandlerMapping）。本测试直接通过 RouterFunctionMapping
     * 对候选路径做 GET/POST 解析，凡能解析到 handler 即证明 servlet path 已映射。最终打印真实
     * 路径供 Task 8 手动冒烟核对。
     */
    @Test
    void mcpEndpointIsMapped() {
        List<String> candidates = List.of(
                "/mcp/sse", "/mcp/message",
                "/mcp/enviro-inspection/sse", "/mcp/enviro-inspection/message",
                "/sse", "/message");

        Set<String> mapped = new LinkedHashSet<>();
        for (String ep : candidates) {
            for (String method : List.of("GET", "POST")) {
                MockHttpServletRequest req = new MockHttpServletRequest(method, ep);
                try {
                    HandlerExecutionChain chain = routerFunctionMapping.getHandler(req);
                    if (chain != null) {
                        mapped.add(method + " " + ep + " -> " + chain.getHandler().getClass().getSimpleName());
                    }
                } catch (Exception ignored) {
                    // 解析异常忽略，继续下一个候选
                }
            }
        }

        assertThat(mapped)
                .as("MCP SSE 端点应已通过 RouterFunctionMapping 映射").isNotEmpty();
        System.out.println("[mcp] mapped routes = " + mapped);
    }
}
