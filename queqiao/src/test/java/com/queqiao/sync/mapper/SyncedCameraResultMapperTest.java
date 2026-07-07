package com.queqiao.sync.mapper;

import com.queqiao.sync.AbstractQueqiaoTest;
import com.queqiao.sync.entity.SyncedCameraResult;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
class SyncedCameraResultMapperTest extends AbstractQueqiaoTest {

    @Autowired
    private SyncedCameraResultMapper cameraMapper;

    private SyncedCameraResult sample(Long id, String status) {
        SyncedCameraResult r = new SyncedCameraResult();
        r.setId(id);
        r.setRecordId(1L);
        r.setCameraCode("CAM-" + id);
        r.setCameraName("摄像头" + id);
        r.setStatus(status);
        r.setQualityScore(new BigDecimal("85.50"));
        r.setScreenshotPath("/s/" + id + ".jpg");
        r.setErrorMessage(null);
        r.setSyncVersion(10L);
        return r;
    }

    @Test
    void upsert_shouldInsertFirstTime() {
        cameraMapper.upsert(sample(1L, "ONLINE"));

        SyncedCameraResult found = cameraMapper.findById(1L);
        assertThat(found).isNotNull();
        assertThat(found.getStatus()).isEqualTo("ONLINE");
        assertThat(found.getQualityScore()).isEqualByComparingTo(new BigDecimal("85.50"));
        assertThat(cameraMapper.count()).isEqualTo(1);
    }

    @Test
    void upsert_shouldBeIdempotent() {
        cameraMapper.upsert(sample(1L, "ONLINE"));
        int afterFirst = cameraMapper.count();

        cameraMapper.upsert(sample(1L, "OFFLINE"));

        assertThat(cameraMapper.count()).isEqualTo(afterFirst);
        assertThat(cameraMapper.findById(1L).getStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void findByRecordId_returnsRowsForRecord() {
        SyncedCameraResult r1 = new SyncedCameraResult();
        r1.setId(1L); r1.setRecordId(100L); r1.setCameraCode("CAM-A");
        r1.setStatus("ONLINE"); r1.setSyncVersion(1L);
        cameraMapper.upsert(r1);

        List<SyncedCameraResult> rows = cameraMapper.findByRecordId(100L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCameraCode()).isEqualTo("CAM-A");
    }

    @Test
    void findLatestPerCamera_returnsOnePerCamera() {
        SyncedCameraResult a1 = new SyncedCameraResult();
        a1.setId(1L); a1.setRecordId(100L); a1.setCameraCode("CAM-A");
        a1.setStatus("ONLINE"); a1.setSyncVersion(1L);
        SyncedCameraResult a2 = new SyncedCameraResult();
        a2.setId(2L); a2.setRecordId(101L); a2.setCameraCode("CAM-A");
        a2.setStatus("OFFLINE"); a2.setSyncVersion(2L);
        cameraMapper.upsert(a1); cameraMapper.upsert(a2);

        List<SyncedCameraResult> latest = cameraMapper.findLatestPerCamera();
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).getCameraCode()).isEqualTo("CAM-A");
        assertThat(latest.get(0).getStatus()).isEqualTo("OFFLINE"); // 取 synced_at 最新
    }
}
