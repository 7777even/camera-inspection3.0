package com.queqiao.sync.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.queqiao.sync.dto.CameraResultDto;
import com.queqiao.sync.dto.InspectionRecordDto;
import com.queqiao.sync.dto.SyncResponse;
import com.queqiao.sync.exception.SyncClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("EnviroBrainSyncClient")
class EnviroBrainSyncClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private EnviroBrainSyncClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        restTemplate.getMessageConverters().stream()
                .filter(c -> c instanceof MappingJackson2HttpMessageConverter)
                .forEach(c -> ((MappingJackson2HttpMessageConverter) c).setObjectMapper(om));
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new EnviroBrainSyncClient(restTemplate, "http://enviro-brain", "secret-key");
    }

    @Test
    @DisplayName("getWatermark 应携带 X-API-Key 并正确解析 watermark")
    void getWatermark_shouldParseWatermark() {
        server.expect(requestTo("http://enviro-brain/api/v1/sync/watermark"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "secret-key"))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"success\",\"data\":{\"watermark\":1709280001234,\"serverTime\":\"2026-07-02T15:00:05\"}}",
                        MediaType.APPLICATION_JSON));

        long wm = client.getWatermark();

        assertThat(wm).isEqualTo(1709280001234L);
        server.verify();
    }

    @Test
    @DisplayName("syncInspections 应解析增量列表、游标与 ISO 日期")
    void syncInspections_shouldParseList() {
        String json = "{\"code\":200,\"message\":\"success\",\"data\":["
                + "{\"id\":1,\"batchId\":\"B1\",\"inspectionDate\":\"2026-07-02\",\"totalCameras\":25,\"onlineCount\":22,\"offlineCount\":2,\"abnormalCount\":1,\"status\":\"COMPLETED\",\"syncVersion\":10},"
                + "{\"id\":2,\"batchId\":\"B2\",\"inspectionDate\":\"2026-07-02\",\"totalCameras\":25,\"onlineCount\":20,\"offlineCount\":3,\"abnormalCount\":2,\"status\":\"COMPLETED\",\"syncVersion\":20}],"
                + "\"hasMore\":false,\"nextSince\":20}";
        server.expect(requestTo("http://enviro-brain/api/v1/sync/inspections?since=0&limit=200"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "secret-key"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        SyncResponse<InspectionRecordDto> resp = client.syncInspections(0, 200);

        assertThat(resp.getData()).hasSize(2);
        assertThat(resp.getData().get(0).getBatchId()).isEqualTo("B1");
        assertThat(resp.getData().get(0).getInspectionDate().toString()).isEqualTo("2026-07-02");
        assertThat(resp.isHasMore()).isFalse();
        assertThat(resp.getNextSince()).isEqualTo(20L);
        server.verify();
    }

    @Test
    @DisplayName("syncCameraResults 应解析摄像头结果（含 BigDecimal 评分）")
    void syncCameraResults_shouldParse() {
        String json = "{\"code\":200,\"message\":\"success\",\"data\":["
                + "{\"id\":100,\"recordId\":1,\"cameraCode\":\"CAM-001\",\"cameraName\":\"摄像头1\",\"status\":\"ONLINE\",\"qualityScore\":85.50,\"screenshotPath\":\"/s/1.jpg\",\"errorMessage\":null,\"syncVersion\":10}],"
                + "\"hasMore\":false,\"nextSince\":10}";
        server.expect(requestTo("http://enviro-brain/api/v1/sync/camera-results?since=0&limit=200"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        SyncResponse<CameraResultDto> resp = client.syncCameraResults(0, 200);

        assertThat(resp.getData()).hasSize(1);
        CameraResultDto c = resp.getData().get(0);
        assertThat(c.getCameraCode()).isEqualTo("CAM-001");
        assertThat(c.getQualityScore()).isEqualByComparingTo(new BigDecimal("85.50"));
        assertThat(c.getStatus()).isEqualTo("ONLINE");
        server.verify();
    }

    @Test
    @DisplayName("非 2xx 响应应抛出 SyncClientException")
    void non2xx_shouldThrow() {
        server.expect(requestTo("http://enviro-brain/api/v1/sync/watermark"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getWatermark())
                .isInstanceOf(SyncClientException.class);
    }
}
