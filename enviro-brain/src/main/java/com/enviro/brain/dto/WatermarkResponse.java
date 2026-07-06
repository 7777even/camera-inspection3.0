package com.enviro.brain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WatermarkResponse {
    private long watermark;
    private String serverTime;
}
