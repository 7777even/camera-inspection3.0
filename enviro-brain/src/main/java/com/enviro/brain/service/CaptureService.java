package com.enviro.brain.service;

import com.enviro.brain.dto.CameraCaptureResult;
import com.enviro.brain.entity.CameraConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class CaptureService {

    @Value("${enviro.python.path:python3}")
    private String pythonPath;

    @Value("${enviro.python.script-path:scripts/camera_capture.py}")
    private String captureScript;

    @Value("${enviro.hikvision.host:}")
    private String hikvisionHost;

    @Value("${enviro.hikvision.port:443}")
    private int hikvisionPort;

    @Value("${enviro.hikvision.app-key:}")
    private String hikvisionAppKey;

    @Value("${enviro.hikvision.app-secret:}")
    private String hikvisionAppSecret;

    @Value("${enviro.hikvision.timeout:15}")
    private int hikvisionTimeout;

    @Value("${enviro.hikvision.retry-count:3}")
    private int hikvisionRetryCount;

    @Value("${enviro.hikvision.warmup-seconds:5.0}")
    private double hikvisionWarmupSeconds;

    @Value("${enviro.hikvision.api-path:/artemis}")
    private String hikvisionApiPath;

    @Value("${enviro.screenshots.dir:./screenshots}")
    private String screenshotsDir;

    private final ObjectMapper mapper = new ObjectMapper();

    public CameraCaptureResult capture(CameraConfig config) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(config));
        pb.redirectErrorStream(true);

        long start = System.currentTimeMillis();
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        long elapsed = System.currentTimeMillis() - start;

        log.info("[Capture] {} 截图完成, exit={}, elapsed={}ms", config.getCameraCode(), exitCode, elapsed);

        if (exitCode != 0) {
            log.warn("[Capture] {} 脚本异常退出: {}", config.getCameraCode(), output);
            CameraCaptureResult error = new CameraCaptureResult();
            error.setStatus("error");
            error.setErrorMsg("脚本退出码 " + exitCode + ": " + output.substring(0, Math.min(output.length(), 200)));
            return error;
        }

        try {
            return parseResult(output);
        } catch (Exception e) {
            log.error("[Capture] {} 结果解析失败: {}", config.getCameraCode(), e.getMessage());
            CameraCaptureResult error = new CameraCaptureResult();
            error.setStatus("error");
            error.setErrorMsg("结果解析失败: " + e.getMessage());
            return error;
        }
    }

    String[] buildCommand(CameraConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add(captureScript);
        cmd.add("--host");  cmd.add(hikvisionHost);
        cmd.add("--port");  cmd.add(String.valueOf(hikvisionPort));
        cmd.add("--app-key"); cmd.add(hikvisionAppKey);
        cmd.add("--app-secret"); cmd.add(hikvisionAppSecret);
        cmd.add("--camera-code"); cmd.add(config.getCameraCode());
        cmd.add("--camera-name"); cmd.add(config.getCameraName() != null ? config.getCameraName() : "");
        cmd.add("--save-dir"); cmd.add(screenshotsDir);
        cmd.add("--timeout"); cmd.add(String.valueOf(hikvisionTimeout));
        cmd.add("--retry-count"); cmd.add(String.valueOf(hikvisionRetryCount));
        cmd.add("--warmup"); cmd.add(String.valueOf(hikvisionWarmupSeconds));
        cmd.add("--api-path"); cmd.add(hikvisionApiPath);
        cmd.add("--json");
        if (config.getArtemisDeviceId() != null && !config.getArtemisDeviceId().isEmpty()) {
            cmd.add("--device-id"); cmd.add(config.getArtemisDeviceId());
        }
        return cmd.toArray(new String[0]);
    }

    CameraCaptureResult parseResult(String json) {
        try {
            return mapper.readValue(json, CameraCaptureResult.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
