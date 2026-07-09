package com.enviro.brain.service;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    @Mock MinioClient minioClient;
    MinioStorageService service;

    @BeforeEach
    void setup() {
        // 构造器本任务新增 6 参签名：在 endpoint/bucket/prefix 后补 retentionDays/cleanupEnabled
        service = new MinioStorageService(minioClient, "http://minio:9000", "bucket", "", 7, true);
    }

    @Test
    void buildObjectKey_includesHour_noPrefix() {
        String key = service.buildObjectKey("三菱化学危废仓库1", LocalDateTime.of(2026, 7, 9, 14, 5));
        assertThat(key).isEqualTo("2026-07-09/三菱化学危废仓库1_14.jpg");
    }

    @Test
    void buildObjectKey_includesHour_withPrefix() {
        MinioStorageService s2 = new MinioStorageService(minioClient, "http://x", "b", "cam", 7, true);
        String key = s2.buildObjectKey("CAM-001", LocalDateTime.of(2026, 7, 9, 9, 0));
        assertThat(key).isEqualTo("cam/2026-07-09/CAM-001_09.jpg");
    }

    @Test
    void parseTimestamp_valid() {
        Optional<LocalDateTime> ts = service.parseTimestampFromKey("2026-07-09/三菱化学危废仓库1_14.jpg");
        assertThat(ts).isPresent();
        assertThat(ts.get()).isEqualTo(LocalDateTime.of(2026, 7, 9, 14, 0));
    }

    @Test
    void parseTimestamp_invalid_returnsEmpty() {
        assertThat(service.parseTimestampFromKey("2026-07-09/oldname.jpg")).isEmpty();
        assertThat(service.parseTimestampFromKey("garbage")).isEmpty();
        assertThat(service.parseTimestampFromKey(null)).isEmpty();
    }

    @Test
    void isExpired_boundaries() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 12, 0);
        assertThat(service.isExpired("2026-07-01/a_12.jpg", now, 7)).isTrue();   // 8 天前 -> 过期
        assertThat(service.isExpired("2026-07-02/a_12.jpg", now, 7)).isFalse();  // 正好 7 天前 -> 不过期（>7 才删）
        assertThat(service.isExpired("2026-07-09/a_12.jpg", now, 7)).isFalse();  // 当天 -> 不过期
        assertThat(service.isExpired("2026-07-09/oldname.jpg", now, 7)).isFalse(); // 解析失败 -> 安全跳过
    }

    @Test
    void selectExpiredKeys_mixed() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 12, 0);
        List<String> keys = List.of(
                "2026-07-01/a_12.jpg",   // 过期
                "2026-07-09/b_12.jpg",   // 不过期
                "2026-07-09/oldname.jpg", // 解析失败，跳过
                "2026-07-02/c_12.jpg");  // 边界不过期
        assertThat(service.selectExpiredKeys(keys, now, 7)).containsExactly("2026-07-01/a_12.jpg");
    }
}
