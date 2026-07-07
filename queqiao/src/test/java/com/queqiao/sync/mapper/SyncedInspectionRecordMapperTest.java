package com.queqiao.sync.mapper;

import com.queqiao.sync.AbstractQueqiaoTest;
import com.queqiao.sync.entity.SyncedInspectionRecord;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
class SyncedInspectionRecordMapperTest extends AbstractQueqiaoTest {

    @Autowired
    private SyncedInspectionRecordMapper inspectionMapper;

    private SyncedInspectionRecord sample(Long id, String status) {
        SyncedInspectionRecord r = new SyncedInspectionRecord();
        r.setId(id);
        r.setBatchId("BATCH-" + id);
        r.setInspectionDate(LocalDate.of(2026, 7, 2));
        r.setTotalCameras(25);
        r.setOnlineCount(20);
        r.setOfflineCount(3);
        r.setAbnormalCount(2);
        r.setStatus(status);
        r.setSyncVersion(10L);
        return r;
    }

    @Test
    void upsert_shouldInsertFirstTime() {
        inspectionMapper.upsert(sample(1L, "RUNNING"));

        SyncedInspectionRecord found = inspectionMapper.findById(1L);
        assertThat(found).isNotNull();
        assertThat(found.getStatus()).isEqualTo("RUNNING");
        assertThat(inspectionMapper.count()).isEqualTo(1);
    }

    @Test
    void upsert_shouldBeIdempotent() {
        inspectionMapper.upsert(sample(1L, "RUNNING"));
        int afterFirst = inspectionMapper.count();

        inspectionMapper.upsert(sample(1L, "COMPLETED"));

        assertThat(inspectionMapper.count()).isEqualTo(afterFirst);
        assertThat(inspectionMapper.findById(1L).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void findByInspectionDate_returnsLatestForDate() {
        SyncedInspectionRecord r = new SyncedInspectionRecord();
        r.setId(10L); r.setBatchId("B1"); r.setInspectionDate(LocalDate.of(2026,7,7));
        r.setTotalCameras(3); r.setOnlineCount(2); r.setOfflineCount(1);
        r.setAbnormalCount(0); r.setStatus("DONE"); r.setSyncVersion(5L);
        inspectionMapper.upsert(r);

        SyncedInspectionRecord found = inspectionMapper.findByInspectionDate(LocalDate.of(2026,7,7));
        assertThat(found).isNotNull();
        assertThat(found.getBatchId()).isEqualTo("B1");
    }
}
