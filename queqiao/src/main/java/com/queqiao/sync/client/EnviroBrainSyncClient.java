package com.queqiao.sync.client;

import com.queqiao.sync.dto.ApiResponse;
import com.queqiao.sync.dto.CameraResultDto;
import com.queqiao.sync.dto.InspectionRecordDto;
import com.queqiao.sync.dto.LedgerRecordDto;
import com.queqiao.sync.dto.SyncResponse;
import com.queqiao.sync.dto.WatermarkResponse;
import com.queqiao.sync.exception.SyncClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 环保小脑同步接口客户端。
 * 携带 {@code X-API-Key} 调用环保小脑的 watermark 与三个增量查询接口，
 * 将响应反序列化为本地 DTO（字段与环保小脑实体严格一致）。
 */
@Slf4j
@Service
public class EnviroBrainSyncClient {

    private static final ParameterizedTypeReference<ApiResponse<WatermarkResponse>> WM_TYPE =
            new ParameterizedTypeReference<ApiResponse<WatermarkResponse>>() {};

    private static final ParameterizedTypeReference<SyncResponse<InspectionRecordDto>> INS_TYPE =
            new ParameterizedTypeReference<SyncResponse<InspectionRecordDto>>() {};

    private static final ParameterizedTypeReference<SyncResponse<CameraResultDto>> CAM_TYPE =
            new ParameterizedTypeReference<SyncResponse<CameraResultDto>>() {};

    private static final ParameterizedTypeReference<SyncResponse<LedgerRecordDto>> LED_TYPE =
            new ParameterizedTypeReference<SyncResponse<LedgerRecordDto>>() {};

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public EnviroBrainSyncClient(RestTemplate restTemplate,
                                 @Value("${enviro-brain.base-url}") String baseUrl,
                                 @Value("${enviro-brain.api-key}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** 查询环保小脑当前全局水位 */
    public long getWatermark() {
        ResponseEntity<ApiResponse<WatermarkResponse>> resp = exchange(
                baseUrl + "/api/v1/sync/watermark", WM_TYPE);
        ApiResponse<WatermarkResponse> body = resp.getBody();
        if (body == null || body.getData() == null) {
            throw new SyncClientException("获取水位失败：响应为空");
        }
        return body.getData().getWatermark();
    }

    /** 增量同步巡检记录 */
    public SyncResponse<InspectionRecordDto> syncInspections(long since, int limit) {
        return exchange(buildUrl("/api/v1/sync/inspections", since, limit), INS_TYPE).getBody();
    }

    /** 增量同步摄像头结果 */
    public SyncResponse<CameraResultDto> syncCameraResults(long since, int limit) {
        return exchange(buildUrl("/api/v1/sync/camera-results", since, limit), CAM_TYPE).getBody();
    }

    /** 增量同步台账记录 */
    public SyncResponse<LedgerRecordDto> syncLedgerRecords(long since, int limit) {
        return exchange(buildUrl("/api/v1/sync/ledger-records", since, limit), LED_TYPE).getBody();
    }

    private String buildUrl(String path, long since, int limit) {
        return baseUrl + path + "?since=" + since + "&limit=" + limit;
    }

    private <T> ResponseEntity<T> exchange(String url, ParameterizedTypeReference<T> typeRef) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.debug("[client] GET {} (带 X-API-Key)", url);
        try {
            ResponseEntity<T> resp = restTemplate.exchange(url, HttpMethod.GET, entity, typeRef);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new SyncClientException("同步接口调用失败: " + url + " -> " + resp.getStatusCode());
            }
            return resp;
        } catch (RestClientException e) {
            // RestTemplate 默认对 4xx/5xx 直接抛 RestClientException，需转换为统一的 SyncClientException
            throw new SyncClientException("同步接口调用失败: " + url, e);
        }
    }
}
