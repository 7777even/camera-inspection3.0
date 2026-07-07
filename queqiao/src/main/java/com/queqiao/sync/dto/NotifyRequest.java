package com.queqiao.sync.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 环保小脑回调通知请求体。
 */
@Data
@NoArgsConstructor
public class NotifyRequest {
    private long syncVersion;
    private String type;
}
