package com.enviro.brain.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.messages.DeleteObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void shouldPrefixObjectKeyWhenPrefixGiven() throws Exception {
        Method m = MinioStorageService.class.getDeclaredMethod("buildObjectKey", String.class, LocalDateTime.class, String.class);
        m.setAccessible(true);
        String key = (String) m.invoke(service, "摄像头A", LocalDateTime.of(2026, 7, 10, 9, 5), "gangqu");
        assertThat(key).startsWith("gangqu/2026-07-10/");
        String key2 = (String) m.invoke(service, "摄像头A", LocalDateTime.of(2026, 7, 10, 9, 5), "");
        assertThat(key2).startsWith("2026-07-10/");
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

    @Test
    void uploadScreenshot_usesHourlyKey() throws Exception {
        byte[] img = new byte[]{1, 2, 3};
        // 固定时间由 buildObjectKey 决定；uploadScreenshot 内部用 LocalDateTime.now()
        // 这里只验证 putObject 被调用且 object 名符合含 _HH 的模式
        service.uploadScreenshot("三菱化学危废仓库1", img);
        ArgumentCaptor<PutObjectArgs> cap = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(cap.capture());
        String objName = cap.getValue().object();
        assertThat(objName).matches(".*/三菱化学危废仓库1_\\d{2}\\.jpg$");
    }

    // MinIO 8.5.7 的 DeleteObject 没有公开 getter，对象名存在私有字段 name 上。
    // 这里通过反射读取，用于断言 removeObjects 只收到过期 key。
    private static String deleteObjectName(DeleteObject d) {
        try {
            Field f = DeleteObject.class.getDeclaredField("name");
            f.setAccessible(true);
            return (String) f.get(d);
        } catch (Exception e) {
            throw new AssertionError("无法读取 DeleteObject.name", e);
        }
    }

    @Test
    void cleanupExpiredObjects_skipsWhenDisabled() throws Exception {
        MinioStorageService disabled = new MinioStorageService(minioClient, "http://x", "b", "", 7, false);
        int n = disabled.cleanupExpiredObjects();
        assertThat(n).isEqualTo(0);
        verify(minioClient, never()).removeObjects(any());
    }

    @Test
    void cleanupExpiredObjects_deletesOnlyExpired() throws Exception {
        MinioStorageService spy = spy(service);
        doReturn(List.of("2026-07-01/a_12.jpg", "2026-07-09/b_12.jpg")).when(spy).listObjectKeys();
        // removeObjects 现会遍历返回的惰性迭代器，必须 stub 为非空（否则遍历 null 会 NPE）
        when(minioClient.removeObjects(any())).thenReturn(Collections.emptyList());
        int n = spy.cleanupExpiredObjects();
        assertThat(n).isEqualTo(1);
        ArgumentCaptor<RemoveObjectsArgs> cap = ArgumentCaptor.forClass(RemoveObjectsArgs.class);
        verify(minioClient).removeObjects(cap.capture());
        // RemoveObjectsArgs.objects() 在 8.5.7 返回 Iterable<DeleteObject>，需收集为 List
        List<DeleteObject> objs = new ArrayList<>();
        cap.getValue().objects().forEach(objs::add);
        assertThat(objs).hasSize(1);
        assertThat(deleteObjectName(objs.get(0))).isEqualTo("2026-07-01/a_12.jpg");
    }
}
