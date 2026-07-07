package com.queqiao.sync.controller;

import com.queqiao.sync.AbstractQueqiaoTest;
import com.queqiao.sync.service.SyncOrchestrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("NotifyController")
class NotifyControllerTest extends AbstractQueqiaoTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SyncOrchestrationService syncOrchestrationService;

    @Test
    @DisplayName("正确 Key 触发同步并返回 200")
    void correctKey_shouldTriggerSync() throws Exception {
        mockMvc.perform(post("/api/notify/new-data")
                        .header("X-API-Key", "test-notify-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncVersion\":123,\"type\":\"inspection_completed\"}"))
                .andExpect(status().isOk());

        verify(syncOrchestrationService).syncOnce();
    }

    @Test
    @DisplayName("错误 Key 返回 401 且不触发同步")
    void wrongKey_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/notify/new-data")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncVersion\":123,\"type\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        verify(syncOrchestrationService, never()).syncOnce();
    }

    @Test
    @DisplayName("同步异常时仍返回 200（异常隔离）")
    void syncFailure_shouldStillReturn200() throws Exception {
        doThrow(new RuntimeException("boom")).when(syncOrchestrationService).syncOnce();

        mockMvc.perform(post("/api/notify/new-data")
                        .header("X-API-Key", "test-notify-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncVersion\":1,\"type\":\"x\"}"))
                .andExpect(status().isOk());
    }
}
