package com.queqiao.sync.scheduler;

import com.queqiao.sync.AbstractQueqiaoTest;
import com.queqiao.sync.client.EnviroBrainSyncClient;
import com.queqiao.sync.service.SyncOrchestrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@DisplayName("SyncScheduler")
class SyncSchedulerTest extends AbstractQueqiaoTest {

    @SpyBean
    private SyncOrchestrationService syncOrchestrationService;

    @MockBean
    private EnviroBrainSyncClient client;

    @org.springframework.beans.factory.annotation.Autowired
    private SyncScheduler syncScheduler;

    @Test
    @DisplayName("scheduledSync 应调用编排服务")
    void scheduledSync_shouldInvokeOrchestration() {
        when(client.getWatermark()).thenReturn(0L);

        syncScheduler.scheduledSync();

        verify(syncOrchestrationService).syncOnce();
    }
}
