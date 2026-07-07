package com.queqiao.sync.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 一轮同步的结果摘要，供测试与日志断言使用。
 */
@Getter
@ToString
@AllArgsConstructor
public class SyncSummary {
    private final long remoteWatermark;
    private final long inspectionsWatermark;
    private final long cameraResultsWatermark;
    private final long ledgerRecordsWatermark;
}
