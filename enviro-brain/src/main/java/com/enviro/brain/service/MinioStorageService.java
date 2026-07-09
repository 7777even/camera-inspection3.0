package com.enviro.brain.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 截图 MinIO 存储服务。
 *
 * <p>将摄像头截图字节上传到 MinIO，返回可访问的完整 URL。
 * 对象键格式：{@code {prefix}/{yyyy-MM-dd}/{safeCameraName}.jpg}（prefix 为空时省略），
 * 例如 {@code 2026-07-08/华达通危废仓库1.jpg}。
 * 对象键不含时分秒，故同一摄像头当天多次上传落到同一 key，MinIO putObject 默认覆盖旧对象，
 * 实现"当天一张图、最新巡检覆盖"的效果（DB 巡检记录仍按小时全量保留）。
 * 其中 safeCameraName 仅剔除路径分隔符、URL 保留字与控制字符，保留中文等多语言字符。
 */
@Slf4j
@Service
public class MinioStorageService {

    private final MinioClient minioClient;
    private final String endpoint;
    private final String bucket;
    private final String prefix;

    public MinioStorageService(MinioClient minioClient,
                               @Value("${enviro.minio.endpoint}") String endpoint,
                               @Value("${enviro.minio.bucket}") String bucket,
                               @Value("${enviro.minio.prefix}") String prefix) {
        this.minioClient = minioClient;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    /**
     * 上传截图字节到 MinIO，返回完整 URL；入参为空时返回 null。
     */
    public String uploadScreenshot(String cameraName, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        ensureBucket();
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String safeName = (cameraName == null || cameraName.isBlank())
                ? UUID.randomUUID().toString()
                : cameraName.replaceAll("[\\\\/:*?\"<>|%#\\x00-\\x1f ]", "_");
        // 当天覆盖：对象键不含时分秒，同一摄像头当天多次上传落到同一 key，MinIO putObject 覆盖旧对象
        String objectKey = (prefix == null || prefix.isBlank())
                ? datePart + "/" + safeName + ".jpg"
                : prefix + "/" + datePart + "/" + safeName + ".jpg";

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(imageBytes), imageBytes.length, -1)
                    .contentType("image/jpeg")
                    .build());
            String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            String url = base + "/" + bucket + "/" + objectKey;
            log.info("[Minio] 截图已上传: {}", url);
            return url;
        } catch (Exception e) {
            log.error("[Minio] 截图上传失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[Minio] 已创建 bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("[Minio] 检查/创建 bucket 失败（可能已存在或无权限）: {}", e.getMessage());
        }
    }
}
