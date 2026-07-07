package com.queqiao.sync.client;

import com.queqiao.sync.dto.ApiResponse;
import com.queqiao.sync.dto.DownloadResultDto;
import com.queqiao.sync.dto.TriggerRequest;
import com.queqiao.sync.dto.TriggerResultDto;
import com.queqiao.sync.exception.SyncClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

@Slf4j
@Service
public class EnviroBrainForwardClient {

    private static final ParameterizedTypeReference<ApiResponse<TriggerResultDto>> TRIGGER_TYPE =
            new ParameterizedTypeReference<ApiResponse<TriggerResultDto>>() {};
    private static final ParameterizedTypeReference<ApiResponse<DownloadResultDto>> DOWNLOAD_TYPE =
            new ParameterizedTypeReference<ApiResponse<DownloadResultDto>>() {};

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public EnviroBrainForwardClient(RestTemplate restTemplate,
                                    @Value("${enviro-brain.base-url}") String baseUrl,
                                    @Value("${enviro-brain.api-key}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** 转发触发巡检到环保小脑 */
    public TriggerResultDto triggerInspection(String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        TriggerRequest body = new TriggerRequest(reason);
        try {
            ResponseEntity<ApiResponse<TriggerResultDto>> resp = restTemplate.exchange(
                    URI.create(baseUrl + "/api/v1/inspections/trigger"),
                    HttpMethod.POST, new HttpEntity<>(body, headers), TRIGGER_TYPE);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null
                    || resp.getBody().getData() == null) {
                throw new SyncClientException("触发巡检失败: " + resp.getStatusCode());
            }
            return resp.getBody().getData();
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new SyncClientException("触发巡检调用失败", e);
        }
    }

    /** 转发台账 docx 下载到环保小脑 */
    public DownloadResultDto downloadLedgerDocx(Long inspectId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        try {
            ResponseEntity<ApiResponse<DownloadResultDto>> resp = restTemplate.exchange(
                    URI.create(baseUrl + "/api/v1/ledger/" + inspectId + "/download"),
                    HttpMethod.GET, new HttpEntity<>(headers), DOWNLOAD_TYPE);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null
                    || resp.getBody().getData() == null) {
                throw new SyncClientException("下载台账失败: " + resp.getStatusCode());
            }
            return resp.getBody().getData();
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new SyncClientException("下载台账调用失败", e);
        }
    }
}
