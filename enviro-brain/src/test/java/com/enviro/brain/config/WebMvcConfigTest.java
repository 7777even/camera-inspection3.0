package com.enviro.brain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(WebMvcConfig.class)
@TestPropertySource(properties = "enviro.api.key=integration-test-key")
@DisplayName("WebMvcConfig 集成测试 — 拦截器注册验证")
class WebMvcConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class TestControllers {

        @RestController
        static class SyncTestController {
            @GetMapping("/api/v1/test/watermark")
            public ResponseEntity<Map<String, String>> watermark() {
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
        }
    }

    @Test
    @DisplayName("有效 API Key 请求 /api/** → 200")
    void validApiKey_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/test/watermark")
                        .header("X-API-Key", "integration-test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("缺少 API Key 请求 /api/** → 401")
    void missingApiKey_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/test/watermark"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Missing X-API-Key header")));
    }

    @Test
    @DisplayName("无效 API Key 请求 /api/** → 401")
    void invalidApiKey_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/test/watermark")
                        .header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid API Key")));
    }
}
