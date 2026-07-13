package com.enviro.brain.service;

import com.enviro.brain.config.ScenarioConfig;
import com.enviro.brain.config.ScenarioConfigs;
import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.InspectionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FeishuNotifyService {

    @Value("${enviro.feishu.webhook-url:}")
    private String webhookUrl;

    @Autowired(required = false)
    private ScenarioConfigs scenarioConfigs;

    private RestTemplate restTemplate = new RestTemplate();

    /**
     * 兼容旧调用方（如 InspectionService）的两参版本，按记录自带场景下发。
     */
    public void sendInspectionReport(InspectionRecord record, List<CameraResult> results) {
        sendInspectionReport(record, results, record != null ? record.getScenario() : null);
    }

    public void sendInspectionReport(InspectionRecord record, List<CameraResult> results, String scenario) {
        String url = resolveWebhook(scenario);
        if (url == null || url.isEmpty()) {
            log.info("[FeishuNotify] Webhook URL 未配置，跳过通知");
            return;
        }

        String title = resolveTitle(scenario);
        String footer = resolveFooter(scenario);
        try {
            String cardJson = buildCardJson(record, results, title, footer);
            log.info("[FeishuNotify] 发送JSON: {}", cardJson);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(cardJson, headers);
            restTemplate.postForObject(url, entity, String.class);
            log.info("[FeishuNotify] 飞书通知发送成功");
        } catch (Exception e) {
            log.error("[FeishuNotify] 飞书通知发送失败: {}", e.getMessage());
        }
    }

    private String resolveWebhook(String scenario) {
        if (scenarioConfigs != null) {
            ScenarioConfig sc = scenarioConfigs.getOrDefault(scenario);
            if (sc != null && sc.getFeishuWebhookUrl() != null && !sc.getFeishuWebhookUrl().isEmpty()) {
                return sc.getFeishuWebhookUrl();
            }
        }
        return webhookUrl;
    }

    private String resolveTitle(String scenario) {
        if (scenarioConfigs != null) {
            ScenarioConfig sc = scenarioConfigs.getOrDefault(scenario);
            if (sc != null && sc.getCardTitle() != null && !sc.getCardTitle().isEmpty()) {
                return sc.getCardTitle();
            }
        }
        return "🔔 摄像头巡检报告";
    }

    private String resolveFooter(String scenario) {
        if (scenarioConfigs != null) {
            ScenarioConfig sc = scenarioConfigs.getOrDefault(scenario);
            if (sc != null && sc.getCardFooter() != null && !sc.getCardFooter().isEmpty()) {
                return sc.getCardFooter();
            }
        }
        return "enviro-brain 自动巡检";
    }

    private String buildCardJson(InspectionRecord record, List<CameraResult> results, String title, String footer) {
        String time = record.getCreatedAt() != null
                ? record.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : "";
        int total = record.getTotalCameras() != null ? record.getTotalCameras() : results.size();
        int online = record.getOnlineCount() != null ? record.getOnlineCount()
                : (int) results.stream().filter(r -> "online".equals(r.getStatus())).count();
        int offline = record.getOfflineCount() != null ? record.getOfflineCount()
                : (int) results.stream().filter(r -> "offline".equals(r.getStatus())).count();
        int abnormal = record.getAbnormalCount() != null ? record.getAbnormalCount()
                : (int) results.stream().filter(r -> "abnormal".equals(r.getStatus())).count();

        StringBuilder detail = new StringBuilder();
        for (CameraResult r : results) {
            if ("online".equals(r.getStatus())) continue;
            if (detail.length() > 0) detail.append("\\n");  // JSON 字面 "\n"，飞书渲染为换行
            String err = r.getErrorMessage() != null ? r.getErrorMessage() : "";
            // 截断+清洗：移除换行（飞书卡片 markdown 不会显示多行错误），避免 JSON 转义混乱
            err = err.replace("\n", " ").replace("\r", " ");
            if (err.length() > 80) err = err.substring(0, 80) + "...";
            detail.append("- ").append(r.getCameraName()).append("：").append(r.getStatus());
            if (!err.isEmpty()) detail.append("（").append(err).append("）");
        }
        String detailStr = detail.length() == 0 ? "无" : detail.toString();

        return "{"
                + "\"msg_type\":\"interactive\","
                + "\"card\":{"
                + "\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"" + title + "\"},\"template\":\"red\"},"
                + "\"elements\":["
                + "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**巡检时间**：" + time + "\"}},"
                + "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**概况**：总 " + total
                + " 路\\n🟢 在线 " + online + "\\n🔴 离线 " + offline + "\\n🟡 异常 " + abnormal + "\"}},"
                + "{\"tag\":\"hr\"},"
                + "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**离线/异常详情**：\\n" + detailStr + "\"}},"
                + "{\"tag\":\"note\",\"elements\":[{\"tag\":\"plain_text\",\"content\":\"" + footer + " " + time + "\"}]}"
                + "]}}";
    }
}
