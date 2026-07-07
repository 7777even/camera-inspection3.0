package com.queqiao.sync.controller;

import com.queqiao.sync.dto.ApiResponse;
import com.queqiao.sync.dto.NotifyRequest;
import com.queqiao.sync.service.SyncOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环保小脑回调接收端点（可选）。
 * 收到环保小脑入库完成通知后，立即触发一轮增量同步。异常隔离，不影响回调返回。
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final SyncOrchestrationService syncOrchestrationService;

    @PostMapping("/new-data")
    public ResponseEntity<ApiResponse<Void>> newData(@RequestBody NotifyRequest request) {
        log.info("[notify] 收到环保小脑回调通知：syncVersion={}, type={}",
                request.getSyncVersion(), request.getType());
        try {
            syncOrchestrationService.syncOnce();
        } catch (Exception e) {
            // 回调触发的同步失败不应导致外网 500，仅记日志
            log.error("[notify] 回调触发同步失败（不影响回调返回）", e);
        }
        return ResponseEntity.ok(ApiResponse.success());
    }
}
