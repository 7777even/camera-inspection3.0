package com.enviro.brain.dto;

import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.entity.InspectionRecord;
import lombok.Data;
import java.util.List;

@Data
public class InspectionContext {
    private Long inspectId;
    private long syncVersion;
    private List<CameraConfig> cameras;
    private InspectionRecord record;
}
