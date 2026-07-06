package com.enviro.brain.controller;

import com.enviro.brain.dto.ImportResult;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.service.CameraConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CameraConfigController.class)
@TestPropertySource(properties = "enviro.api.key=test-api-key")
@DisplayName("CameraConfigController")
class CameraConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CameraConfigService cameraConfigService;

    @Nested
    @DisplayName("GET /api/v1/cameras")
    class ListCameras {

        @Test
        void shouldReturnActiveCameras() throws Exception {
            CameraConfig cam1 = new CameraConfig();
            cam1.setCameraCode("CAM-01");
            cam1.setCameraName("摄像头1");
            CameraConfig cam2 = new CameraConfig();
            cam2.setCameraCode("CAM-02");
            cam2.setCameraName("摄像头2");

            when(cameraConfigService.findActive(1, 10)).thenReturn(Arrays.asList(cam1, cam2));
            when(cameraConfigService.countActive()).thenReturn(2);

            mockMvc.perform(get("/api/v1/cameras")
                            .header("X-API-Key", "test-api-key")
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.items", hasSize(2)))
                    .andExpect(jsonPath("$.data.items[0].cameraCode").value("CAM-01"))
                    .andExpect(jsonPath("$.data.items[1].cameraCode").value("CAM-02"));
        }

        @Test
        void shouldReturnEmptyListWhenNoCameras() throws Exception {
            when(cameraConfigService.findActive(1, 10)).thenReturn(Collections.emptyList());
            when(cameraConfigService.countActive()).thenReturn(0);

            mockMvc.perform(get("/api/v1/cameras")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.items", hasSize(0)));
        }

        @Test
        void shouldUseDefaultPagination() throws Exception {
            when(cameraConfigService.findActive(1, 10)).thenReturn(Collections.emptyList());
            when(cameraConfigService.countActive()).thenReturn(0);

            mockMvc.perform(get("/api/v1/cameras")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk());

            verify(cameraConfigService).findActive(1, 10);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/cameras/{cameraCode}")
    class GetCamera {

        @Test
        void shouldReturnCameraWhenFound() throws Exception {
            CameraConfig cam = new CameraConfig();
            cam.setCameraCode("CAM-001");
            cam.setCameraName("测试摄像头");
            cam.setEnterprise("测试企业");
            when(cameraConfigService.findByCameraCode("CAM-001")).thenReturn(cam);

            mockMvc.perform(get("/api/v1/cameras/CAM-001")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.cameraCode").value("CAM-001"))
                    .andExpect(jsonPath("$.data.cameraName").value("测试摄像头"));
        }

        @Test
        void shouldReturn404WhenNotFound() throws Exception {
            when(cameraConfigService.findByCameraCode("NONEXIST"))
                    .thenThrow(new RuntimeException("摄像头不存在: NONEXIST"));

            mockMvc.perform(get("/api/v1/cameras/NONEXIST")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/cameras/import")
    class ImportCameras {

        @Test
        void shouldImportExcelSuccessfully() throws Exception {
            ImportResult result = ImportResult.builder()
                    .imported(2)
                    .updated(1)
                    .errors(0)
                    .build();
            when(cameraConfigService.importExcel(any(byte[].class), eq("cameras.xlsx")))
                    .thenReturn(result);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "cameras.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "fake excel content".getBytes()
            );

            mockMvc.perform(multipart("/api/v1/cameras/import")
                            .file(file)
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.imported").value(2))
                    .andExpect(jsonPath("$.data.updated").value(1))
                    .andExpect(jsonPath("$.data.errors").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/cameras/template")
    class DownloadTemplate {

        @Test
        void shouldDownloadExcelTemplate() throws Exception {
            byte[] templateBytes = new byte[]{1, 2, 3, 4, 5};
            when(cameraConfigService.generateTemplate()).thenReturn(templateBytes);

            mockMvc.perform(get("/api/v1/cameras/template")
                            .header("X-API-Key", "test-api-key"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .andExpect(header().string("Content-Disposition",
                            "form-data; name=\"attachment\"; filename=\"camera_import_template.xlsx\""));
        }
    }
}
