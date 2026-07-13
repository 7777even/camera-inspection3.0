package com.enviro.brain.service;

import com.enviro.brain.dto.ImportResult;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.mapper.CameraConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CameraConfigService")
class CameraConfigServiceTest {

    @Mock
    private CameraConfigMapper cameraConfigMapper;

    @InjectMocks
    private CameraConfigService cameraConfigService;

    @Nested
    @DisplayName("findActive()")
    class FindActive {

        @Test
        void shouldReturnActiveCameras() {
            CameraConfig cam1 = new CameraConfig();
            cam1.setCameraCode("CAM-01");
            CameraConfig cam2 = new CameraConfig();
            cam2.setCameraCode("CAM-02");
            when(cameraConfigMapper.findActive(0, 10)).thenReturn(Arrays.asList(cam1, cam2));

            List<CameraConfig> result = cameraConfigService.findActive(1, 10);

            assertThat(result).hasSize(2);
            verify(cameraConfigMapper).findActive(0, 10);
        }

        @Test
        void shouldCalculateOffsetFromPage() {
            when(cameraConfigMapper.findActive(20, 5)).thenReturn(Collections.emptyList());

            cameraConfigService.findActive(5, 5);

            verify(cameraConfigMapper).findActive(20, 5);
        }
    }

    @Nested
    @DisplayName("findByCameraCode()")
    class FindByCameraCode {

        @Test
        void shouldReturnCameraWhenFound() {
            CameraConfig cam = new CameraConfig();
            cam.setCameraCode("CAM-001");
            cam.setCameraName("Test Camera");
            when(cameraConfigMapper.findByCameraCode("CAM-001")).thenReturn(cam);

            CameraConfig result = cameraConfigService.findByCameraCode("CAM-001");

            assertThat(result.getCameraCode()).isEqualTo("CAM-001");
            assertThat(result.getCameraName()).isEqualTo("Test Camera");
        }

        @Test
        void shouldThrowWhenNotFound() {
            when(cameraConfigMapper.findByCameraCode("NONEXIST")).thenReturn(null);

            assertThatThrownBy(() -> cameraConfigService.findByCameraCode("NONEXIST"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("摄像头不存在");
        }
    }

    @Nested
    @DisplayName("importExcel()")
    class ImportExcel {

        @Test
        void shouldImportNewCameras() throws Exception {
            when(cameraConfigMapper.findByCameraCode(anyString())).thenReturn(null);
            when(cameraConfigMapper.upsert(any())).thenReturn(1);

            byte[] excelBytes = createMinimalExcel(
                    "camera_code,camera_name,enterprise,rtsp_url,artemis_device_id,location\n" +
                    "CAM-NEW-01,新摄像头1,企业A,rtsp://a/stream,dev001,位置A\n" +
                    "CAM-NEW-02,新摄像头2,企业B,rtsp://b/stream,dev002,位置B\n"
            );

            ImportResult result = cameraConfigService.importExcel(excelBytes, "test.xlsx");

            assertThat(result.getImported()).isEqualTo(2);
            assertThat(result.getUpdated()).isEqualTo(0);
            assertThat(result.getErrors()).isEqualTo(0);
            verify(cameraConfigMapper, times(2)).upsert(any());
        }

        @Test
        void shouldUpdateExistingCameras() throws Exception {
            CameraConfig existingCam = new CameraConfig();
            existingCam.setCameraCode("CAM-EXIST");
            existingCam.setCameraName("旧名称");
            when(cameraConfigMapper.findByCameraCode("CAM-EXIST")).thenReturn(existingCam);
            when(cameraConfigMapper.upsert(any())).thenReturn(1);

            byte[] excelBytes = createMinimalExcel(
                    "camera_code,camera_name,enterprise,rtsp_url,artemis_device_id,location\n" +
                    "CAM-EXIST,新名称,新企业,rtsp://new/stream,newdev,新位置\n"
            );

            ImportResult result = cameraConfigService.importExcel(excelBytes, "test.xlsx");

            assertThat(result.getImported()).isEqualTo(0);
            assertThat(result.getUpdated()).isEqualTo(1);
            assertThat(result.getErrors()).isEqualTo(0);
        }

        @Test
        void shouldRejectNonExcelFile() {
            byte[] content = "not an excel file".getBytes();

            assertThatThrownBy(() -> cameraConfigService.importExcel(content, "test.txt"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("仅支持 .xlsx 或 .xls 格式");
        }

        @Test
        void shouldRejectMissingCameraCodeColumn() {
            byte[] excelBytes = createMinimalExcel(
                    "camera_name,enterprise,rtsp_url,location\n" +
                    "摄像头1,企业A,rtsp://a/stream,位置A\n"
            );

            assertThatThrownBy(() -> cameraConfigService.importExcel(excelBytes, "test.xlsx"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("缺少必填列");
        }
    }

    @Nested
    @DisplayName("generateTemplate()")
    class GenerateTemplate {

        @Test
        void shouldGenerateNonEmptyExcel() throws Exception {
            byte[] template = cameraConfigService.generateTemplate();

            assertThat(template).isNotNull();
            assertThat(template.length).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("findActiveByScenario()")
    class ScenarioFilter {
        @Test
        void shouldReturnOnlyMatchingScenario() {
            CameraConfig enviro = new CameraConfig(); enviro.setCameraCode("E1"); enviro.setCameraName("环保1"); enviro.setEnabled(1); enviro.setScenario("enviro");
            CameraConfig gangqu = new CameraConfig(); gangqu.setCameraCode("G1"); gangqu.setCameraName("港区1"); gangqu.setEnabled(1); gangqu.setScenario("gangqu");
            when(cameraConfigMapper.findActiveByScenario(eq("gangqu"), anyInt(), anyInt())).thenReturn(List.of(gangqu));
            when(cameraConfigMapper.countByScenario("gangqu")).thenReturn(1);
            List<CameraConfig> result = cameraConfigService.findActiveByScenario(1, 100, "gangqu");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getScenario()).isEqualTo("gangqu");
            assertThat(cameraConfigService.countByScenario("gangqu")).isEqualTo(1);
        }
    }

    /**
     * Create a minimal .xlsx file from CSV content using Apache POI
     */
    private byte[] createMinimalExcel(String csvContent) {
        try {
            org.apache.poi.ss.usermodel.Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet();
            String[] lines = csvContent.split("\n");
            for (int i = 0; i < lines.length; i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(i);
                String[] cells = lines[i].split(",");
                for (int j = 0; j < cells.length; j++) {
                    row.createCell(j).setCellValue(cells[j]);
                }
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            workbook.write(bos);
            workbook.close();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test Excel", e);
        }
    }
}
