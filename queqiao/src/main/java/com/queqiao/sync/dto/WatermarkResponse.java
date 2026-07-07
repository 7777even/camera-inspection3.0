package com.queqiao.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 水位响应体，与环保小脑 {@code WatermarkResponse} 契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatermarkResponse {
    private long watermark;
    private String serverTime;
}
