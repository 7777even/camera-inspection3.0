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
    private SyncedInspectionRecordMapper mapper;

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
        mapper.upsert(sample(1L, "RUNNING"));

        SyncedInspectionRecord found = mapper.findById(1L);
        assertThat(found).isNotNull();
        assertThat(found.getStatus()).isEqualTo("RUNNING");
        assertThat(mapper.count()).isEqualTo(1);
    }

    @Test
    void upsert_shouldBeIdempotent() {
        mapper.upsert(sample(1L, "RUNNING"));
        int afterFirst = mapper.count();

        mapper.upsert(sample(1L, "COMPLETED"));

        assertThat(mapper.count()).isEqualTo(afterFirst);
        assertThat(mapper.findById(1L).getStatus()).isEqualTo("COMPLETED");
    }
}
