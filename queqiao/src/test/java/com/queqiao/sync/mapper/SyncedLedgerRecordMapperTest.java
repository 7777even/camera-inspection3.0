package com.queqiao.sync.mapper;

import com.queqiao.sync.AbstractQueqiaoTest;
import com.queqiao.sync.entity.SyncedLedgerRecord;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
class SyncedLedgerRecordMapperTest extends AbstractQueqiaoTest {

    @Autowired
    private SyncedLedgerRecordMapper mapper;

    private SyncedLedgerRecord sample(Long id) {
        SyncedLedgerRecord r = new SyncedLedgerRecord();
        r.setId(id);
        r.setRecordId(1L);
        r.setInspectionDate(LocalDate.of(2026, 7, 2));
        r.setContent("#1 | 摄像头 | OFFLINE | 异常");
        r.setDocxPath("/ledger/" + id + ".docx");
        r.setSyncVersion(10L);
        return r;
    }

    @Test
    void upsert_shouldInsertFirstTime() {
        mapper.upsert(sample(1L));

        SyncedLedgerRecord found = mapper.findById(1L);
        assertThat(found).isNotNull();
        assertThat(found.getContent()).contains("OFFLINE");
        assertThat(mapper.count()).isEqualTo(1);
    }

    @Test
    void upsert_shouldBeIdempotent() {
        mapper.upsert(sample(1L));
        int afterFirst = mapper.count();

        mapper.upsert(sample(1L));

        assertThat(mapper.count()).isEqualTo(afterFirst);
    }
}
