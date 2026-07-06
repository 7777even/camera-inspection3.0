package com.enviro.brain.dto;

import lombok.Data;

@Data
public class CameraCaptureResult {
    private String status;
    private Double qualityScore;
    private String screenshotPath;
    private String errorMsg;
    private String captureTime;
    private Integer retryUsed = 0;
    private String qualityDetail;
}
