package com.queqiao.sync.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAuthInterceptorTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    private McpAuthInterceptor interceptor(boolean enabled, String apiKey) {
        McpAuthInterceptor i = new McpAuthInterceptor();
        ReflectionTestUtils.setField(i, "enabled", enabled);
        ReflectionTestUtils.setField(i, "apiKey", apiKey);
        return i;
    }

    @Test
    void disabled_alwaysPasses() {
        McpAuthInterceptor i = interceptor(false, "");
        assertThat(i.preHandle(request, response, handler)).isTrue();
    }

    @Test
    void enabled_withMatchingKey_passes() {
        when(request.getHeader("X-API-Key")).thenReturn("secret-123");
        McpAuthInterceptor i = interceptor(true, "secret-123");
        assertThat(i.preHandle(request, response, handler)).isTrue();
    }

    @Test
    void enabled_withWrongKey_rejects401() {
        when(request.getHeader("X-API-Key")).thenReturn("wrong");
        McpAuthInterceptor i = interceptor(true, "secret-123");
        assertThat(i.preHandle(request, response, handler)).isFalse();
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void enabled_withMissingKey_rejects401() {
        when(request.getHeader("X-API-Key")).thenReturn(null);
        McpAuthInterceptor i = interceptor(true, "secret-123");
        assertThat(i.preHandle(request, response, handler)).isFalse();
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void enabled_withBlankConfiguredKey_rejectsToAvoidOpenDoor() {
        when(request.getHeader("X-API-Key")).thenReturn("anything");
        McpAuthInterceptor i = interceptor(true, "");
        assertThat(i.preHandle(request, response, handler)).isFalse();
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
    }
}
