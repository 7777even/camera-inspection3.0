package com.enviro.brain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class QueqiaoNotifyService {

    @Value("${enviro.queqiao.callback-url:}")
    private String callbackUrl;

    @Value("${enviro.queqiao.notify-api-key:queqiao-notify-key-2026}")
    private String notifyApiKey;

    private RestTemplate restTemplate = new RestTemplate();

    public void notifyNewData(long syncVersion) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-Key", notifyApiKey);
            String body = "{\"syncVersion\":" + syncVersion + ",\"type\":\"inspection_completed\"}";
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.postForObject(callbackUrl, entity, String.class);
            log.info("[QueqiaoNotify] 回调成功, syncVersion={}", syncVersion);
        } catch (Exception e) {
            log.warn("[QueqiaoNotify] 回调失败: {}", e.getMessage());
        }
    }
}
