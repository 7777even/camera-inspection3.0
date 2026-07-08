package com.enviro.brain.service;

import com.enviro.brain.entity.CameraResult;
import com.enviro.brain.entity.LedgerRecord;
import com.enviro.brain.mapper.LedgerRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

@Service
@Slf4j
public class LedgerService {

    private final LedgerRecordMapper ledgerRecordMapper;

    @Value("${enviro.ledger.template-path:templates/危废仓库巡查台账_新模版.docx}")
    private String templatePath;

    @Value("${enviro.ledger.dir:./ledger}")
    private String ledgerDir;

    @Value("${enviro.screenshots.dir:./screenshots}")
    private String screenshotsDir;

    public LedgerService(LedgerRecordMapper ledgerRecordMapper) {
        this.ledgerRecordMapper = ledgerRecordMapper;
    }

    /**
     * 基于模板生成台账 Word 文档，同时写入 ledger_records 表。
     *
     * @return docx 文件路径，如无目标记录则返回 null
     */
    public String generateAndSave(Long inspectId, List<CameraResult> targets, long syncVersion) {
        if (targets == null || targets.isEmpty()) {
            log.info("[Ledger] 无需登记的台账记录");
            return null;
        }

        LocalDate now = LocalDate.now();
        String fileName = "台账_" + now + ".docx";
        String docxPath = ledgerDir + "/" + now + "/" + fileName;

        // 写入 DB 记录
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

        // 生成实际 docx 文件
        try {
            generateDocx(now, targets, docxPath);
        } catch (Exception e) {
            log.error("[Ledger] 生成 docx 文件失败: {}", e.getMessage(), e);
        }

        log.info("[Ledger] 台账生成完成: {}", docxPath);
        return docxPath;
    }

    /**
     * 基于模板生成 Word 文档。
     */
    private void generateDocx(LocalDate date, List<CameraResult> targets, String docxPath) throws Exception {
        // 确保目录存在
        Path dir = Path.of(docxPath).getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }

        // 复制模板
        File templateFile = new File(templatePath);
        if (!templateFile.exists()) {
            log.warn("[Ledger] 模板文件不存在: {}，跳过 docx 生成", templatePath);
            return;
        }

        try (FileInputStream fis = new FileInputStream(templateFile);
             XWPFDocument doc = new XWPFDocument(fis)) {

            // 更新日期：假设表格第一行第一列包含日期
            List<XWPFTable> tables = doc.getTables();
            if (!tables.isEmpty()) {
                XWPFTable table = tables.get(0);
                // Row 0 是标题行（日期 + 合并单元格）
                if (table.getRows().size() > 0) {
                    XWPFTableRow headerRow = table.getRow(0);
                    String dateStr = date.format(DateTimeFormatter.ofPattern("yyyy.M.d"));
                    for (XWPFTableCell cell : headerRow.getTableCells()) {
                        String oldText = cell.getText().trim();
                        if (oldText.contains("巡查日期")) {
                            // 清空段落并设置新文本
                            for (XWPFParagraph p : cell.getParagraphs()) {
                                p.removeRun(0);
                            }
                            XWPFParagraph p = cell.getParagraphs().get(0);
                            p.createRun().setText("巡查日期：" + dateStr);
                        }
                    }
                }
            }

            // 更新每路摄像头的行
            XWPFTable table = tables.isEmpty() ? null : tables.get(0);
            int updated = 0;
            if (table != null) {
                for (CameraResult cam : targets) {
                    boolean found = false;
                    for (int ri = 2; ri < table.getRows().size(); ri++) { // 跳过 row0(标题) + row1(表头)
                        XWPFTableRow row = table.getRow(ri);
                        List<XWPFTableCell> cells = row.getTableCells();
                        if (cells.size() >= 5) {
                            String templateName = normalizeName(cells.get(2).getText());
                            String cameraName = normalizeName(cam.getCameraName());
                            if (templateName.equals(cameraName)) {
                                BigDecimal qs = cam.getQualityScore();
                                setCellText(cells.get(3), qs != null && qs.doubleValue() < 0.5 ? "是" : "否");
                                String anomaly = cam.getErrorMessage() != null ? cam.getErrorMessage() : "";
                                if ("offline".equals(cam.getStatus())) {
                                    anomaly = "离线: " + anomaly;
                                } else if (qs != null && qs.doubleValue() < 0.3) {
                                    anomaly = "画面质量差(评分" + String.format("%.2f", qs.doubleValue()) + ")";
                                }
                                setCellText(cells.get(4), anomaly);
                                // 嵌入截图到第6列（监控截图）
                                if (cells.size() >= 6 && cam.getScreenshotPath() != null) {
                                    embedImage(cells.get(5), cam.getScreenshotPath());
                                }
                                found = true;
                                updated++;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        log.warn("[Ledger] 模板中未找到摄像头: {}，跳过", cam.getCameraName());
                    }
                }
            }
            log.info("[Ledger] docx 更新完成: 共处理 {} 行", updated);

            // 保存
            try (FileOutputStream fos = new FileOutputStream(docxPath)) {
                doc.write(fos);
            }
        }
    }

    private void setCellText(XWPFTableCell cell, String text) {
        // 清空旧内容
        for (int i = cell.getParagraphs().size() - 1; i >= 0; i--) {
            cell.removeParagraph(i);
        }
        XWPFParagraph p = cell.addParagraph();
        p.createRun().setText(text != null ? text : "");
    }

    /**
     * 在单元格中嵌入截图图片。
     */
    private void embedImage(XWPFTableCell cell, String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) return;
        File imgFile = new File(imagePath);
        if (!imgFile.exists()) return;
        try (FileInputStream fis = new FileInputStream(imgFile)) {
            // 清空旧内容
            for (int i = cell.getParagraphs().size() - 1; i >= 0; i--) {
                cell.removeParagraph(i);
            }
            XWPFParagraph p = cell.addParagraph();
            XWPFRun run = p.createRun();
            // 读取原图尺寸，等比缩放至单元格宽度（约 3.2cm = 120px）
            BufferedImage bi = ImageIO.read(imgFile);
            int srcW = bi.getWidth();
            int srcH = bi.getHeight();
            int targetPxW = 120;
            int targetPxH = (int) ((double) srcH / srcW * targetPxW);
            run.addPicture(fis, XWPFDocument.PICTURE_TYPE_JPEG, imagePath,
                    Units.toEMU(targetPxW), Units.toEMU(targetPxH));
        } catch (Exception e) {
            log.warn("[Ledger] 嵌入截图失败: {} - {}", imagePath, e.getMessage());
            XWPFParagraph p = cell.addParagraph();
            p.createRun().setText("截图失败");
        }
    }

    /**
     * 获取指定巡检 ID 对应的台账文件。
     */
    public File getLedgerFile(Long inspectId) {
        // 从最新一条 LedgerRecord 获取 docxPath
        List<LedgerRecord> records = ledgerRecordMapper.findByRecordId(inspectId);
        if (records.isEmpty()) {
            return null;
        }
        String docxPath = records.get(0).getDocxPath();
        if (docxPath == null || docxPath.isEmpty()) {
            return null;
        }
        File file = new File(docxPath);
        return file.exists() ? file : null;
    }

    /**
     * 规范化摄像头名称：去空格、去连接线、统一全半角，用于模板模糊匹配。
     */
    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("[\\s\\-–—]", "")   // 去空格 + 各种连接线
                .replaceAll("[（(]", "(")
                .replaceAll("[）)]", ")")
                .toLowerCase();
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
