package com.enviro.brain.controller;

import com.enviro.brain.dto.ApiResponse;
import com.enviro.brain.dto.DownloadResultDto;
import com.enviro.brain.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Slf4j
public class LedgerController {

    private final LedgerService ledgerService;

    /**
     * 获取台账元信息（MCP forward 工具调用此端点返回 JSON）。
     */
    @GetMapping("/{inspectId}/download")
    public ResponseEntity<ApiResponse<DownloadResultDto>> getLedgerInfo(@PathVariable Long inspectId) {
        log.info("[Ledger] 获取台账信息, inspectId={}", inspectId);
        File file = ledgerService.getLedgerFile(inspectId);
        if (file == null || !file.exists()) {
            return ResponseEntity.notFound().build();
        }
        DownloadResultDto dto = new DownloadResultDto(
                inspectId,
                file.getName(),
                file.getAbsolutePath()
        );
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * 直接下载 docx 文件（HTTP 客户端直接访问）。
     */
    @GetMapping("/{inspectId}/file")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long inspectId) {
        File file = ledgerService.getLedgerFile(inspectId);
        if (file == null || !file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
