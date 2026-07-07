package com.queqiao.sync.dto.view;

import com.queqiao.sync.entity.SyncedInspectionRecord;
import lombok.Data;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
public class InspectionSummaryView {
    private LocalDate start;
    private LocalDate end;
    private double onlineRate;
    private SyncedInspectionRecord worstDay;
    private List<Map<String, Object>> frequentOfflineCameras = Collections.emptyList();
    private String message;

    public static InspectionSummaryView empty(LocalDate start, LocalDate end) {
        InspectionSummaryView v = new InspectionSummaryView();
        v.setStart(start);
        v.setEnd(end);
        v.setMessage("区间内暂无巡检数据");
        return v;
    }
}
