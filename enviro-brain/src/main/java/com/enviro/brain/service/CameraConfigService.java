package com.enviro.brain.service;

import com.enviro.brain.dto.ImportResult;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.mapper.CameraConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CameraConfigService {

    private static final List<String> REQUIRED_COLUMNS = List.of("camera_code");
    private static final List<String> ALL_COLUMNS = List.of(
            "camera_code", "camera_name", "enterprise", "rtsp_url", "artemis_device_id", "location"
    );

    private final CameraConfigMapper cameraConfigMapper;

    public List<CameraConfig> findActive(int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return cameraConfigMapper.findActive(offset, size);
    }

    public int countActive() {
        return cameraConfigMapper.countActive();
    }

    public List<CameraConfig> findActiveByScenario(int page, int size, String scenario) {
        int offset = (page - 1) * size;
        return cameraConfigMapper.findActiveByScenario(scenario, offset, size);
    }

    public int countByScenario(String scenario) {
        return cameraConfigMapper.countByScenario(scenario);
    }

    public CameraConfig findByCameraCode(String cameraCode) {
        CameraConfig config = cameraConfigMapper.findByCameraCode(cameraCode);
        if (config == null) {
            throw new RuntimeException("摄像头不存在: " + cameraCode);
        }
        return config;
    }

    @Transactional
    public ImportResult importExcel(byte[] fileBytes, String originalFilename) {
        if (!originalFilename.matches(".*\\.(xlsx|xls)$")) {
            throw new RuntimeException("仅支持 .xlsx 或 .xls 格式的文件");
        }

        int imported = 0;
        int updated = 0;
        int errors = 0;
        List<String> errorDetails = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(fileBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 2) {
                throw new RuntimeException("Excel 文件为空或只有表头");
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new RuntimeException("缺少表头行");
            }

            // 索引映射：列名 -> 列号
            int cameraCodeIdx = -1;
            int cameraNameIdx = -1;
            int enterpriseIdx = -1;
            int rtspUrlIdx = -1;
            int artemisDeviceIdIdx = -1;
            int locationIdx = -1;

            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                if (cell == null) continue;
                String colName = cell.getStringCellValue().trim().toLowerCase();
                switch (colName) {
                    case "camera_code" -> cameraCodeIdx = i;
                    case "camera_name" -> cameraNameIdx = i;
                    case "enterprise" -> enterpriseIdx = i;
                    case "rtsp_url" -> rtspUrlIdx = i;
                    case "artemis_device_id" -> artemisDeviceIdIdx = i;
                    case "location" -> locationIdx = i;
                }
            }

            if (cameraCodeIdx < 0) {
                throw new RuntimeException("缺少必填列: camera_code");
            }

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;

                try {
                    String cameraCode = getCellString(row, cameraCodeIdx);
                    if (cameraCode == null || cameraCode.isBlank()) {
                        errors++;
                        errorDetails.add("第" + (rowIndex + 1) + "行: camera_code 为空");
                        continue;
                    }

                    String cameraName = cameraNameIdx >= 0 ? getCellString(row, cameraNameIdx) : cameraCode;
                    String enterprise = enterpriseIdx >= 0 ? getCellString(row, enterpriseIdx) : null;
                    String rtspUrl = rtspUrlIdx >= 0 ? getCellString(row, rtspUrlIdx) : null;
                    String artemisDeviceId = artemisDeviceIdIdx >= 0 ? getCellString(row, artemisDeviceIdIdx) : null;
                    String location = locationIdx >= 0 ? getCellString(row, locationIdx) : null;

                    CameraConfig config = new CameraConfig();
                    config.setCameraCode(cameraCode);
                    config.setCameraName(cameraName != null ? cameraName : cameraCode);
                    config.setEnterprise(enterprise);
                    config.setRtspUrl(rtspUrl);
                    config.setArtemisDeviceId(artemisDeviceId);
                    config.setLocation(location);
                    config.setEnabled(1);

                    CameraConfig existing = cameraConfigMapper.findByCameraCode(cameraCode);
                    cameraConfigMapper.upsert(config);
                    if (existing == null) {
                        imported++;
                    } else {
                        updated++;
                    }
                } catch (Exception e) {
                    errors++;
                    errorDetails.add("第" + (rowIndex + 1) + "行: " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 Excel 文件失败: " + e.getMessage(), e);
        }

        return ImportResult.builder()
                .imported(imported)
                .updated(updated)
                .errors(errors)
                .errorDetails(errorDetails)
                .build();
    }

    public byte[] generateTemplate() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("摄像头清单");
            Row headerRow = sheet.createRow(0);
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            for (int i = 0; i < ALL_COLUMNS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(ALL_COLUMNS.get(i));
                cell.setCellStyle(headerStyle);
            }

            // 添加示例行
            Row exampleRow = sheet.createRow(1);
            exampleRow.createCell(0).setCellValue("CAM001");
            exampleRow.createCell(1).setCellValue("1号摄像头");
            exampleRow.createCell(2).setCellValue("示例企业");
            exampleRow.createCell(3).setCellValue("rtsp://192.168.1.100:554/stream");
            exampleRow.createCell(4).setCellValue("device-001");
            exampleRow.createCell(5).setCellValue("车间A-东侧");

            // 自动调整列宽
            for (int i = 0; i < ALL_COLUMNS.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    private String getCellString(Row row, int colIndex) {
        if (colIndex < 0) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }
}
