package com.enviro.brain.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueqiaoNotifyService")
class QueqiaoNotifyServiceTest {

    @Mock private RestTemplate restTemplate;
    private QueqiaoNotifyService service;

    @BeforeEach
    void setUp() {
        service = new QueqiaoNotifyService();
        ReflectionTestUtils.setField(service, "callbackUrl", "http://queqiao/api/notify/new-data");
        ReflectionTestUtils.setField(service, "notifyApiKey", "queqiao-notify-key-2026");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("should POST to callback URL")
    void shouldPostToCallback() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("ok");
        service.notifyNewData(42L);
        verify(restTemplate).postForObject(
                eq("http://queqiao/api/notify/new-data"),
                any(HttpEntity.class),
                eq(String.class)
        );
    }

    @Test
    @DisplayName("should send X-API-Key header")
    void shouldSendApiKeyHeader() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("ok");
        service.notifyNewData(42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(anyString(), captor.capture(), eq(String.class));

        HttpHeaders headers = captor.getValue().getHeaders();
        Assertions.assertEquals("queqiao-notify-key-2026", headers.getFirst("X-API-Key"));
    }

    @Test
    @DisplayName("should skip when URL is empty")
    void shouldSkipWhenEmpty() {
        ReflectionTestUtils.setField(service, "callbackUrl", "");
        service.notifyNewData(42L);
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("should not throw on failure")
    void shouldNotThrowOnFailure() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));
        service.notifyNewData(42L);
    }
}
