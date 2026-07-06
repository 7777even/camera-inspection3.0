package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeishuNotifyService")
class FeishuNotifyServiceTest {

    private FeishuNotifyService feishuNotifyService;
    @Mock private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        feishuNotifyService = new FeishuNotifyService();
        ReflectionTestUtils.setField(feishuNotifyService, "webhookUrl", "https://open.feishu.cn/webhook/test");
        ReflectionTestUtils.setField(feishuNotifyService, "restTemplate", restTemplate);
    }

    private InspectionRecord record() {
        InspectionRecord r = new InspectionRecord();
        r.setId(1L);
        r.setBatchId("auto-20260706-1500");
        r.setInspectionDate(LocalDate.of(2026, 7, 6));
        r.setTotalCameras(3);
        r.setOnlineCount(2);
        r.setOfflineCount(1);
        r.setAbnormalCount(0);
        r.setStatus("COMPLETED");
        r.setCreatedAt(LocalDateTime.of(2026, 7, 6, 15, 0, 5));
        return r;
    }

    private List<CameraResult> results() {
        CameraResult r1 = new CameraResult();
        r1.setCameraCode("CAM001"); r1.setCameraName("危废仓库1"); r1.setStatus("online");
        r1.setQualityScore(new BigDecimal("0.85"));

        CameraResult r2 = new CameraResult();
        r2.setCameraCode("CAM002"); r2.setCameraName("危废仓库2"); r2.setStatus("offline");
        r2.setErrorMessage("Connection refused");

        CameraResult r3 = new CameraResult();
        r3.setCameraCode("CAM003"); r3.setCameraName("危废仓库3"); r3.setStatus("online");
        r3.setQualityScore(new BigDecimal("0.90"));

        return Arrays.asList(r1, r2, r3);
    }

    @Nested
    @DisplayName("sendInspectionReport()")
    class SendReport {

        @Test
        @DisplayName("should post card to webhook URL")
        void shouldPostCardToWebhook() {
            when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenReturn("ok");

            feishuNotifyService.sendInspectionReport(record(), results());

            verify(restTemplate).postForObject(
                    eq("https://open.feishu.cn/webhook/test"),
                    any(HttpEntity.class),
                    eq(String.class)
            );
        }

        @Test
        @DisplayName("should build card with inspection summary")
        void shouldBuildCardWithSummary() {
            ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.postForObject(anyString(), captor.capture(), eq(String.class)))
                    .thenReturn("ok");

            feishuNotifyService.sendInspectionReport(record(), results());

            String body = (String) captor.getValue().getBody();
            assertThat(body).contains("3").contains("2").contains("1"); // total, online, offline
            assertThat(body).contains("危废仓库2"); // offline camera
        }

        @Test
        @DisplayName("should skip when webhook URL is empty")
        void shouldSkipWhenUrlEmpty() {
            ReflectionTestUtils.setField(feishuNotifyService, "webhookUrl", "");

            feishuNotifyService.sendInspectionReport(record(), results());

            verify(restTemplate, never()).postForObject(anyString(), any(), any());
        }

        @Test
        @DisplayName("should not throw when webhook unreachable")
        void shouldNotThrowWhenUnreachable() {
            when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("Connection timeout"));

            // should not throw
            feishuNotifyService.sendInspectionReport(record(), results());
        }
    }
}
