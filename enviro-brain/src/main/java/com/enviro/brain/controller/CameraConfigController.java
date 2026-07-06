package com.enviro.brain.controller;

import com.enviro.brain.dto.ApiResponse;
import com.enviro.brain.dto.ImportResult;
import com.enviro.brain.entity.CameraConfig;
import com.enviro.brain.service.CameraConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cameras")
@RequiredArgsConstructor
public class CameraConfigController {

    private final CameraConfigService cameraConfigService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCameras(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        List<CameraConfig> cameras = cameraConfigService.findActive(page, size);
        int total = cameraConfigService.countActive();

        Map<String, Object> data = new HashMap<>();
        data.put("items", cameras);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/{cameraCode}")
    public ResponseEntity<ApiResponse<CameraConfig>> getCamera(@PathVariable String cameraCode) {
        try {
            CameraConfig camera = cameraConfigService.findByCameraCode(cameraCode);
            return ResponseEntity.ok(ApiResponse.success(camera));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, e.getMessage()));
        }
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<ImportResult>> importCameras(
            @RequestParam("file") MultipartFile file) throws IOException {

        ImportResult result = cameraConfigService.importExcel(
                file.getBytes(), file.getOriginalFilename());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() throws Exception {
        byte[] templateBytes = cameraConfigService.generateTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "camera_import_template.xlsx");

        return ResponseEntity.ok().headers(headers).body(templateBytes);
    }
}
