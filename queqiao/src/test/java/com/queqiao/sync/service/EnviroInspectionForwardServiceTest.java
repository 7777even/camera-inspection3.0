package com.queqiao.sync.service;

import com.queqiao.sync.client.EnviroBrainForwardClient;
import com.queqiao.sync.dto.DownloadResultDto;
import com.queqiao.sync.dto.TriggerResultDto;
import com.queqiao.sync.exception.SyncClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnviroInspectionForwardServiceTest {

    @Mock EnviroBrainForwardClient client;
    @InjectMocks EnviroInspectionForwardService service;

    @Test
    void triggerInspection_success() {
        TriggerResultDto r = new TriggerResultDto("TASK-123", true);
        when(client.triggerInspection("手动触发")).thenReturn(r);

        Map<String, Object> result = service.triggerInspection("手动触发");
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("taskId")).isEqualTo("TASK-123");
    }

    @Test
    void triggerInspection_degradeWhenUnreachable() {
        when(client.triggerInspection("x")).thenThrow(new SyncClientException("连接失败"));

        Map<String, Object> result = service.triggerInspection("x");
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("message").toString()).contains("环保小脑暂不可用");
    }

    @Test
    void downloadLedgerDocx_success() {
        DownloadResultDto d = new DownloadResultDto(10L, "ledger.docx", "/tmp/ledger.docx");
        when(client.downloadLedgerDocx(10L)).thenReturn(d);

        Map<String, Object> result = service.downloadLedgerDocx(10L);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("fileName")).isEqualTo("ledger.docx");
    }

    @Test
    void downloadLedgerDocx_degradeWhenUnreachable() {
        when(client.downloadLedgerDocx(10L)).thenThrow(new SyncClientException("连接失败"));

        Map<String, Object> result = service.downloadLedgerDocx(10L);
        assertThat(result.get("ok")).isEqualTo(false);
    }
}
