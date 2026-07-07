package com.queqiao.sync.service;

import com.queqiao.sync.dto.view.CameraStatusView;
import com.queqiao.sync.dto.view.InspectionLedgerView;
import com.queqiao.sync.dto.view.InspectionSummaryView;
import com.queqiao.sync.entity.SyncedCameraResult;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import com.queqiao.sync.mapper.SyncedCameraResultMapper;
import com.queqiao.sync.mapper.SyncedInspectionRecordMapper;
import com.queqiao.sync.mapper.SyncedLedgerRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnviroInspectionQueryServiceTest {

    @Mock SyncedInspectionRecordMapper inspectionMapper;
    @Mock SyncedCameraResultMapper cameraMapper;
    @Mock SyncedLedgerRecordMapper ledgerMapper;
    @InjectMocks EnviroInspectionQueryService service;

    @Test
    void getInspectionLedger_filtersByStatus() {
        SyncedInspectionRecord ins = new SyncedInspectionRecord();
        ins.setId(1L); ins.setInspectionDate(LocalDate.of(2026,7,7));
        SyncedCameraResult on = new SyncedCameraResult(); on.setStatus("ONLINE");
        SyncedCameraResult off = new SyncedCameraResult(); off.setStatus("OFFLINE");

        when(inspectionMapper.findByInspectionDate(LocalDate.of(2026,7,7))).thenReturn(ins);
        when(cameraMapper.findByRecordId(1L)).thenReturn(List.of(on, off));
        when(ledgerMapper.findByRecordId(1L)).thenReturn(new SyncedLedgerRecord());

        InspectionLedgerView v = service.getInspectionLedger(LocalDate.of(2026,7,7), "OFFLINE", null);
        assertThat(v.getInspection()).isSameAs(ins);
        assertThat(v.getCameras()).hasSize(1);
        assertThat(v.getCameras().get(0).getStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void getInspectionLedger_emptyWhenNoInspection() {
        when(inspectionMapper.findByInspectionDate(LocalDate.of(2026,7,7))).thenReturn(null);
        InspectionLedgerView v = service.getInspectionLedger(LocalDate.of(2026,7,7), null, null);
        assertThat(v.getMessage()).isEqualTo("当日暂无同步数据");
    }

    @Test
    void getCameraStatus_singleCameraReturnsSnapshotAndHistory() {
        SyncedCameraResult latest = new SyncedCameraResult(); latest.setCameraCode("CAM-A");
        latest.setStatus("OFFLINE"); latest.setSyncVersion(9L);
        SyncedCameraResult older = new SyncedCameraResult(); older.setCameraCode("CAM-A");
        older.setStatus("ONLINE"); older.setSyncVersion(1L);

        when(cameraMapper.findByCameraCode("CAM-A")).thenReturn(List.of(latest, older));
        CameraStatusView v = service.getCameraStatus("CAM-A", 7);
        assertThat(v.getSnapshot()).isSameAs(latest);
        assertThat(v.getCameras()).hasSize(2); // 近 7 天历史
    }

    @Test
    void getInspectionSummary_computesOnlineRateAndWorstDay() {
        SyncedInspectionRecord r1 = new SyncedInspectionRecord();
        r1.setId(1L); r1.setInspectionDate(LocalDate.of(2026,7,1));
        r1.setTotalCameras(10); r1.setOnlineCount(9); r1.setOfflineCount(1); r1.setAbnormalCount(0);
        SyncedInspectionRecord r2 = new SyncedInspectionRecord();
        r2.setId(2L); r2.setInspectionDate(LocalDate.of(2026,7,2));
        r2.setTotalCameras(10); r2.setOnlineCount(5); r2.setOfflineCount(4); r2.setAbnormalCount(1);

        when(inspectionMapper.findByRange(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2)))
                .thenReturn(List.of(r1, r2));
        when(cameraMapper.findByRecordIds(List.of(1L, 2L))).thenReturn(List.of());

        InspectionSummaryView v = service.getInspectionSummary(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2));
        assertThat(v.getOnlineRate()).isEqualTo(0.7); // (9+5)/(10+10)
        assertThat(v.getWorstDay().getId()).isEqualTo(2L); // 离线+异常最多
    }

    @Test
    void getInspectionSummary_emptyRange() {
        when(inspectionMapper.findByRange(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2)))
                .thenReturn(List.of());
        InspectionSummaryView v = service.getInspectionSummary(LocalDate.of(2026,7,1), LocalDate.of(2026,7,2));
        assertThat(v.getMessage()).isEqualTo("区间内暂无巡检数据");
    }
}
