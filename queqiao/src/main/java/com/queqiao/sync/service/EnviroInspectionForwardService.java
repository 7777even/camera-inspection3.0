package com.queqiao.sync.service;

import com.queqiao.sync.client.EnviroBrainForwardClient;
import com.queqiao.sync.dto.DownloadResultDto;
import com.queqiao.sync.dto.TriggerResultDto;
import com.queqiao.sync.exception.SyncClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnviroInspectionForwardService {

    private static final String UNREACHABLE = "环保小脑暂不可用，请稍后重试";

    private final EnviroBrainForwardClient client;

    /** 触发巡检；环保小脑不可达时返回友好错误而非抛异常 */
    public Map<String, Object> triggerInspection(String reason) {
        try {
            TriggerResultDto r = client.triggerInspection(reason);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("taskId", r.getTaskId());
            m.put("accepted", r.isAccepted());
            return m;
        } catch (SyncClientException e) {
            log.warn("[mcp][forward] 触发巡检降级：{}", e.getMessage());
            return degrade();
        }
    }

    /** 下载台账 docx；不可达时降级 */
    public Map<String, Object> downloadLedgerDocx(Long inspectId) {
        try {
            DownloadResultDto d = client.downloadLedgerDocx(inspectId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("inspectId", d.getInspectId());
            m.put("fileName", d.getFileName());
            m.put("docxPath", d.getDocxPath());
            return m;
        } catch (SyncClientException e) {
            log.warn("[mcp][forward] 下载台账降级：{}", e.getMessage());
            return degrade();
        }
    }

    private Map<String, Object> degrade() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", UNREACHABLE);
        return m;
    }
}
