package com.enviro.brain.controller;

import com.enviro.brain.config.WebMvcConfig;
import com.enviro.brain.dto.InspectionContext;
import com.enviro.brain.entity.InspectionRecord;
import com.enviro.brain.service.InspectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InspectionController.class)
@Import(WebMvcConfig.class)
@TestPropertySource(properties = "enviro.api.key=integration-test-key")
@DisplayName("InspectionController")
class InspectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InspectionService inspectionService;

    @Test
    @DisplayName("POST /trigger -> 202 with taskId (async)")
    void trigger_shouldReturn202Async() throws Exception {
        InspectionContext ctx = new InspectionContext();
        ctx.setInspectId(42L);
        ctx.setSyncVersion(99L);
        ctx.setRecord(new InspectionRecord());
        when(inspectionService.prepareInspection(anyString())).thenReturn(ctx);

        mockMvc.perform(post("/api/v1/inspections/trigger")
                        .header("X-API-Key", "integration-test-key")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value(42))
                .andExpect(jsonPath("$.data.status").value("running"));

        verify(inspectionService).prepareInspection("manual");
        verify(inspectionService).runInspectionAsync(ctx);
        verify(inspectionService, never()).executeInspection(anyString());
    }

    @Test
    @DisplayName("POST /trigger without API Key -> 401")
    void triggerWithoutApiKey_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/inspections/trigger")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
