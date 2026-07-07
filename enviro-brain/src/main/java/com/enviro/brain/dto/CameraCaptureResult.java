package com.enviro.brain.dto;

import lombok.Data;

import java.util.Map;

@Data
public class CameraCaptureResult {
    private String status;
    private Double qualityScore;
    private String screenshotPath;
    private String errorMsg;
    private String captureTime;
    private Integer retryUsed = 0;
    /** 质量详情（来自脚本的 JSON 对象，如 laplacianScore / brightnessScore / colorDiversityScore） */
    private Map<String, Object> qualityDetail;
    /** 脚本实际使用的 RTSP 流地址 */
    private String rtspUrl;
    /** 捕获帧数 */
    private Integer totalFrames;
}
