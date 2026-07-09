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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final int retentionDays;
    private final boolean cleanupEnabled;

    public MinioStorageService(MinioClient minioClient,
                               @Value("${enviro.minio.endpoint}") String endpoint,
                               @Value("${enviro.minio.bucket}") String bucket,
                               @Value("${enviro.minio.prefix}") String prefix,
                               int retentionDays,
                               boolean cleanupEnabled) {
        this.minioClient = minioClient;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.prefix = prefix;
        this.retentionDays = retentionDays;
        this.cleanupEnabled = cleanupEnabled;
    }

    private static final Pattern KEY_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2}).*_(\\d{2})\\.jpg$");

    /** 生成对象键：{prefix}/{yyyy-MM-dd}/{safeName}_{HH}.jpg（HH 取自 time）。time 可注入便于测试。 */
    public String buildObjectKey(String cameraName, LocalDateTime time) {
        String datePart = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String hh = time.format(DateTimeFormatter.ofPattern("HH"));
        String safeName = (cameraName == null || cameraName.isBlank())
                ? UUID.randomUUID().toString()
                : cameraName.replaceAll("[\\\\/:*?\"<>|%#\\x00-\\x1f ]", "_");
        return (prefix == null || prefix.isBlank())
                ? datePart + "/" + safeName + "_" + hh + ".jpg"
                : prefix + "/" + datePart + "/" + safeName + "_" + hh + ".jpg";
    }

    /** 从 key 解析"日期+小时"；解析失败返回 empty（调用方据此跳过，绝不误删）。 */
    public Optional<LocalDateTime> parseTimestampFromKey(String key) {
        if (key == null) return Optional.empty();
        Matcher m = KEY_PATTERN.matcher(key);
        if (!m.find()) return Optional.empty();
        try {
            LocalDate d = LocalDate.parse(m.group(1));
            int hh = Integer.parseInt(m.group(2));
            return Optional.of(LocalDateTime.of(d, LocalTime.of(hh, 0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 对象是否过期：年龄(天) > retentionDays 才视为过期。key 解析失败 -> false（安全跳过）。 */
    public boolean isExpired(String key, LocalDateTime now, int retentionDays) {
        return parseTimestampFromKey(key)
                .map(ts -> ChronoUnit.DAYS.between(ts, now) > retentionDays)
                .orElse(false);
    }

    /** 纯函数：从给定 key 列表选出过期的（不改 IO）。 */
    public List<String> selectExpiredKeys(Collection<String> keys, LocalDateTime now, int retentionDays) {
        List<String> out = new ArrayList<>();
        if (keys == null) return out;
        for (String k : keys) {
            if (isExpired(k, now, retentionDays)) out.add(k);
        }
        return out;
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
