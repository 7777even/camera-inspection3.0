package com.queqiao.sync.dto.view;

import com.queqiao.sync.entity.SyncedCameraResult;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class CameraStatusView {
    private SyncedCameraResult snapshot;          // 单摄像头查询的最新快照
    private List<SyncedCameraResult> cameras = Collections.emptyList(); // 全局查询：每摄像头最新
    private String message;

    public static CameraStatusView empty() {
        CameraStatusView v = new CameraStatusView();
        v.setMessage("暂无摄像头状态数据");
        return v;
    }
}
