package com.queqiao.sync.dto.view;

import com.queqiao.sync.entity.SyncedCameraResult;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
public class InspectionLedgerView {
    private LocalDate inspectionDate;
    private SyncedInspectionRecord inspection;
    private List<SyncedCameraResult> cameras = Collections.emptyList();
    private SyncedLedgerRecord ledger;
    private LocalDateTime syncedAt;
    private String message;

    public static InspectionLedgerView empty(LocalDate d) {
        InspectionLedgerView v = new InspectionLedgerView();
        v.setInspectionDate(d);
        v.setMessage("当日暂无同步数据");
        return v;
    }
}
