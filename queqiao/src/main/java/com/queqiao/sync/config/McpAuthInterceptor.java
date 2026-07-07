package com.queqiao.sync.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * MCP 端点可选鉴权拦截器。
 * 默认关闭（{@code queqiao.mcp.auth.enabled=false}），关闭时直接放行，不影响 Phase 4 联调；
 * 启用后（{@code enabled=true} 且配置了 {@code queqiao.mcp.auth.api-key}）对 /mcp/** 做 X-API-Key 校验。
 * 注意：外部网关已做 Bearer 校验时，本拦截器一般保持关闭。
 */
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
