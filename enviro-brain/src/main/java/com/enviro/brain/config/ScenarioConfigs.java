package com.enviro.brain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "enviro.scenarios")
public class ScenarioConfigs {
    private final Map<String, ScenarioConfig> scenarios = new LinkedHashMap<>();

    public Map<String, ScenarioConfig> getScenarios() {
        return scenarios;
    }

    /** 按场景取配置，缺省回退到 enviro 场景 */
    public ScenarioConfig getOrDefault(String scenario) {
        ScenarioConfig sc = scenarios.get(scenario);
        return sc != null ? sc : scenarios.get("enviro");
    }
}
