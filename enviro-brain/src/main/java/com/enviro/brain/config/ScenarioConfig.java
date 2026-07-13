package com.enviro.brain.config;

import lombok.Data;

@Data
public class ScenarioConfig {
    private String feishuWebhookUrl = "";
    private String cardTitle = "";
    private String cardFooter = "";
    private String minioPrefix = "";
}
