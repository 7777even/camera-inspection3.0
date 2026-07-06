package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.LedgerRecord;
import com.enviro.brain.mapper.LedgerRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class LedgerService {

    private final LedgerRecordMapper ledgerRecordMapper;

    @Value("${enviro.ledger.template-path:templates/危废仓库巡查台账_新模版.docx}")
    private String templatePath;

    @Value("${enviro.ledger.dir:./ledger}")
    private String ledgerDir;

    public LedgerService(LedgerRecordMapper ledgerRecordMapper) {
        this.ledgerRecordMapper = ledgerRecordMapper;
    }

    /**
     * 生成台账并写入 ledger_records 表。
     * @return docx 文件路径，如无目标记录则返回 null
     */
    public String generateAndSave(Long inspectId, List<CameraResult> targets, long syncVersion) {
        if (targets == null || targets.isEmpty()) {
            log.info("[Ledger] 无需登记的台账记录");
            return null;
        }

        log.info("[Ledger] 开始生成台账，共 {} 条记录", targets.size());
        LocalDate now = LocalDate.now();
        String fileName = "台账_" + now + ".docx";
        String docxPath = ledgerDir + "/" + now + "/" + fileName;

        int seq = 1;
        for (CameraResult cam : targets) {
            LedgerRecord record = new LedgerRecord();
            record.setRecordId(inspectId);
            record.setInspectionDate(now);
            record.setContent(buildContent(cam, seq));
            record.setDocxPath(docxPath);
            record.setSyncVersion(syncVersion);
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            ledgerRecordMapper.insert(record);
            seq++;
        }

        log.info("[Ledger] 台账生成完成: {}", docxPath);
        return docxPath;
    }

    private String buildContent(CameraResult cam, int seq) {
        return String.format("#%d %s | %s | 质量:%s | %s",
                seq,
                cam.getCameraName(),
                cam.getStatus(),
                cam.getQualityScore() != null ? cam.getQualityScore().toString() : "N/A",
                cam.getErrorMessage() != null ? cam.getErrorMessage() : "");
    }

    boolean shouldRegisterToLedger(CameraResult result) {
        if ("offline".equals(result.getStatus()) || "abnormal".equals(result.getStatus())) {
            return true;
        }
        if (result.getQualityScore() != null && result.getQualityScore().compareTo(new BigDecimal("0.5")) < 0) {
            return true;
        }
        return false;
    }
}
