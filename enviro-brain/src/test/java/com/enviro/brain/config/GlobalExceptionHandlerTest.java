package com.enviro.brain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ExceptionTestController.class)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "enviro.api.key=test-api-key")
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    @RestController
    static class ExceptionTestController {

        @GetMapping("/api/v1/test/exception")
        public ResponseEntity<String> throwException() {
            throw new RuntimeException("测试运行时异常");
        }

        @GetMapping("/api/v1/test/illegal-arg")
        public ResponseEntity<String> throwIllegalArgument() {
            throw new IllegalArgumentException("参数错误");
        }

        @GetMapping("/api/v1/test/bind-error")
        public ResponseEntity<String> throwBindException() throws BindException {
            throw new BindException(new Object(), "testObject");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("@ExceptionHandler(Exception.class) → 500")
    class GeneralException {

        @Test
        @DisplayName("RuntimeException → 500 + ApiResponse 格式")
        void runtimeExceptionShouldReturn500() throws Exception {
            mockMvc.perform(get("/api/v1/test/exception")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value(containsString("测试运行时异常")));
        }

        @Test
        @DisplayName("IllegalArgumentException → 500 + ApiResponse 格式")
        void illegalArgumentExceptionShouldReturn500() throws Exception {
            mockMvc.perform(get("/api/v1/test/illegal-arg")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value(containsString("参数错误")));
        }
    }

    @Nested
    @DisplayName("@ExceptionHandler(BindException.class) → 400")
    class BindExceptionHandler {

        @Test
        @DisplayName("BindException → 400 + ApiResponse 格式")
        void bindExceptionShouldReturn400() throws Exception {
            mockMvc.perform(get("/api/v1/test/bind-error")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }
}
