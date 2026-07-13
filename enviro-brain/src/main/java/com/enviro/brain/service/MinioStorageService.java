package com.enviro.brain.service;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
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
 * 对象键格式：{@code {prefix}/{yyyy-MM-dd}/{safeCameraName}_{HH}.jpg}（prefix 为空时省略），
 * 例如 {@code 2026-07-08/华达通危废仓库1_14.jpg}。
 * 对象键含两位小时（HH），同一摄像头每小时落到不同 key，避免跨小时巡检时 MinIO putObject
 * 覆盖上一小时的最新截图（DB 巡检记录仍按小时全量保留）。
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
                           @Value("${enviro.minio.retention-days:7}") int retentionDays,
                           @Value("${enviro.minio.cleanup.enabled:true}") boolean cleanupEnabled) {
        this.minioClient = minioClient;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.prefix = prefix;
        this.retentionDays = retentionDays;
        this.cleanupEnabled = cleanupEnabled;
    }

    private static final Pattern KEY_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2}).*_(\\d{2})\\.jpg$");

    private static final int DELETE_CHUNK = 1000;

    /** 生成对象键（带显式 prefix）。prefix 非空时形如 {prefix}/{yyyy-MM-dd}/{safeName}_{HH}.jpg；
     *  否则（prefix 为 null 或空串）省略前缀段：{yyyy-MM-dd}/{safeName}_{HH}.jpg（HH 取自 time）。 */
    private String buildObjectKey(String cameraName, LocalDateTime time, String prefix) {
        String datePart = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String hh = time.format(DateTimeFormatter.ofPattern("HH"));
        String safeName = (cameraName == null || cameraName.isBlank())
                ? UUID.randomUUID().toString()
                : cameraName.replaceAll("[\\\\/:*?\"<>|%#\\x00-\\x1f ]", "_");
        return (prefix != null && !prefix.isEmpty())
                ? prefix + "/" + datePart + "/" + safeName + "_" + hh + ".jpg"
                : datePart + "/" + safeName + "_" + hh + ".jpg";
    }

    /** 兼容重载：使用注入的字段 prefix 生成对象键（time 可注入便于测试）。 */
    public String buildObjectKey(String cameraName, LocalDateTime time) {
        return buildObjectKey(cameraName, time, this.prefix);
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
     * 使用注入的字段 prefix（向后兼容旧调用方，环保场景）。
     */
    public String uploadScreenshot(String cameraName, byte[] imageBytes) {
        return uploadScreenshot(cameraName, imageBytes, null);
    }

    /**
     * 上传截图字节到 MinIO，返回完整 URL；入参为空时返回 null。
     * prefix 非空时使用该显式前缀（场景维度，如 gangqu/），否则回退到字段 prefix。
     */
    public String uploadScreenshot(String cameraName, byte[] imageBytes, String prefix) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        ensureBucket();
        String effectivePrefix = (prefix != null && !prefix.isEmpty()) ? prefix : this.prefix;
        String objectKey = buildObjectKey(cameraName, LocalDateTime.now(), effectivePrefix);

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

    private String effectivePrefix() {
        return (prefix == null || prefix.isBlank()) ? "" : prefix + "/";
    }

    /** 列出桶内（按 prefix）全部对象名。IO 方法，供清理编排调用。 */
    public List<String> listObjectKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(effectivePrefix()).recursive(true).build());
        for (Result<Item> r : results) {
            keys.add(r.get().objectName());
        }
        return keys;
    }

    /** 批量删除给定 key；按 1000 上限分片调用 removeObjects（MinIO/S3 单请求上限）。
     *  返回实际成功删除的数量（本批提交数 - 报错数；MinIO 仅在删除出错时才在结果迭代器中产出项，
     *  因此成功删除一批会得到空结果集，此时计数 = 提交数）。 */
    public int deleteObjects(List<String> keys) throws Exception {
        if (keys == null || keys.isEmpty()) return 0;
        int deleted = 0;
        for (int i = 0; i < keys.size(); i += DELETE_CHUNK) {
            List<String> sub = keys.subList(i, Math.min(i + DELETE_CHUNK, keys.size()));
            List<DeleteObject> objs = sub.stream()
                    .map(DeleteObject::new)
                    .collect(java.util.stream.Collectors.toList());
            // removeObjects 返回惰性迭代器：实际删除请求仅在遍历结果时才会真正发出。
            // 若不消费该 iterable，生产中不会执行任何删除（清理功能静默失效）。
            // 结果迭代器中只会包含"删除出错"的对象；遍历并调用 r.get() 既能触发真实删除，
            // 也能捕获单个对象的删除异常（出错计数，不中断整批）。
            Iterable<Result<DeleteError>> results = minioClient.removeObjects(
                    RemoveObjectsArgs.builder().bucket(bucket).objects(objs).build());
            int errors = 0;
            for (Result<DeleteError> r : results) {
                try {
                    r.get(); // 触发真实删除；成功则无异常
                } catch (Exception e) {
                    errors++;
                    log.error("[Minio] 删除单个过期对象失败: {}", e.getMessage(), e);
                }
            }
            deleted += (sub.size() - errors);
        }
        return deleted;
    }

    /** 编排：cleanup.enabled=false 直接返回 0；否则列出->选过期->删除，返回删除数量。 */
    public int cleanupExpiredObjects() {
        if (!cleanupEnabled) {
            log.info("[Minio] 清理已禁用(cleanup.enabled=false)，跳过");
            return 0;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<String> keys = listObjectKeys();
            List<String> expired = selectExpiredKeys(keys, now, retentionDays);
            if (expired.isEmpty()) {
                log.info("[Minio] 无过期截图需清理");
                return 0;
            }
            int deleted = deleteObjects(expired);
            log.info("[Minio] 清理过期截图 {} 张", deleted);
            return deleted;
        } catch (Exception e) {
            log.error("[Minio] 清理过期截图失败: {}", e.getMessage(), e);
            return 0;
        }
    }
}
