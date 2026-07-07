package com.queqiao.sync.mapper;

import com.queqiao.sync.AbstractQueqiaoTest;
import com.queqiao.sync.entity.SyncedCameraResult;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
class SyncedCameraResultMapperTest extends AbstractQueqiaoTest {

    @Autowired
    private SyncedCameraResultMapper mapper;

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
        mapper.upsert(sample(1L, "ONLINE"));

        SyncedCameraResult found = mapper.findById(1L);
        assertThat(found).isNotNull();
        assertThat(found.getStatus()).isEqualTo("ONLINE");
        assertThat(found.getQualityScore()).isEqualByComparingTo(new BigDecimal("85.50"));
        assertThat(mapper.count()).isEqualTo(1);
    }

    @Test
    void upsert_shouldBeIdempotent() {
        mapper.upsert(sample(1L, "ONLINE"));
        int afterFirst = mapper.count();

        mapper.upsert(sample(1L, "OFFLINE"));

        assertThat(mapper.count()).isEqualTo(afterFirst);
        assertThat(mapper.findById(1L).getStatus()).isEqualTo("OFFLINE");
    }
}
