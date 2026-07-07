package com.queqiao.sync.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API Key 认证拦截器，保护回调接收端点 {@code /api/notify/**}，
 * 校验请求头 {@code X-API-Key} 是否匹配配置的 {@code queqiao.notify.api-key}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    @Value("${queqiao.notify.api-key}")
    private String expectedKey;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String key = request.getHeader("X-API-Key");
        if (expectedKey != null && expectedKey.equals(key)) {
            return true;
        }
        log.warn("[auth] 回调接口鉴权失败，远程地址={}", request.getRemoteAddr());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return false;
    }
}
