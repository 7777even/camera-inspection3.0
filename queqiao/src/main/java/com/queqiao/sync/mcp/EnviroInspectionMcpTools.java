package com.queqiao.sync.mcp;

import com.queqiao.sync.dto.view.CameraStatusView;
import com.queqiao.sync.dto.view.InspectionLedgerView;
import com.queqiao.sync.dto.view.InspectionSummaryView;
import com.queqiao.sync.dto.view.OperationResultView;
import com.queqiao.sync.service.EnviroInspectionForwardService;
import com.queqiao.sync.service.EnviroInspectionQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnviroInspectionMcpTools {

    private final EnviroInspectionQueryService queryService;
    private final EnviroInspectionForwardService forwardService;

    @Tool(description = "获取指定日期危废仓库巡查台账：含巡检汇总、各摄像头结果与台账记录。不穿透环保小脑，读鹊桥自有库。")
    public InspectionLedgerView getInspectionLedger(
            @ToolParam(description = "巡检日期（yyyy-MM-dd）；不传则取当天") LocalDate date,
            @ToolParam(description = "摄像头状态过滤：ONLINE / OFFLINE / ABNORMAL；可空") String status,
            @ToolParam(description = "企业名过滤（预留，暂未启用）；可空") String enterprise) {
        log.info("[mcp] getInspectionLedger date={} status={} enterprise={}", date, status, enterprise);
        return queryService.getInspectionLedger(date, status, enterprise);
    }

    @Tool(description = "获取摄像头状态：指定摄像头名返回最新快照与近 N 天历史；不指定则返回每个摄像头最新一条。")
    public CameraStatusView getCameraStatus(
            @ToolParam(description = "摄像头编码/名称；不传则返回所有摄像头最新一条") String cameraName,
            @ToolParam(description = "历史天数 N，默认 7；仅 cameraName 有值时生效") Integer historyDays) {
        log.info("[mcp] getCameraStatus cameraName={} historyDays={}", cameraName, historyDays);
        return queryService.getCameraStatus(cameraName, historyDays);
    }

    @Tool(description = "获取区间内巡检汇总：在线率、最差记录日、频繁离线摄像头排名。")
    public InspectionSummaryView getInspectionSummary(
            @ToolParam(description = "区间开始日期（yyyy-MM-dd）") LocalDate start,
            @ToolParam(description = "区间结束日期（yyyy-MM-dd）") LocalDate end) {
        log.info("[mcp] getInspectionSummary start={} end={}", start, end);
        return queryService.getInspectionSummary(start, end);
    }

    @Tool(description = "触发环保小脑执行一次巡检（操作类，转发环保小脑；不可达时返回友好错误）。")
    public OperationResultView triggerInspection(
            @ToolParam(description = "触发巡检的原因/备注；可空") String reason) {
        log.info("[mcp] triggerInspection reason={}", reason);
        return OperationResultView.from(forwardService.triggerInspection(reason));
    }

    @Tool(description = "下载指定巡检记录的台账 Word 文档（操作类，转发环保小脑；不可达时返回友好错误）。")
    public OperationResultView downloadLedgerDocx(
            @ToolParam(description = "巡检记录 ID（inspectId）") Long inspectId) {
        log.info("[mcp] downloadLedgerDocx inspectId={}", inspectId);
        return OperationResultView.from(forwardService.downloadLedgerDocx(inspectId));
    }
}
