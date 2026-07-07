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
    private SyncedLedgerRecordMapper ledgerMapper;

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
        ledgerMapper.upsert(sample(1L));

        SyncedLedgerRecord found = ledgerMapper.findById(1L);
        assertThat(found).isNotNull();
        assertThat(found.getContent()).contains("OFFLINE");
        assertThat(ledgerMapper.count()).isEqualTo(1);
    }

    @Test
    void upsert_shouldBeIdempotent() {
        ledgerMapper.upsert(sample(1L));
        int afterFirst = ledgerMapper.count();

        ledgerMapper.upsert(sample(1L));

        assertThat(ledgerMapper.count()).isEqualTo(afterFirst);
    }

    @Test
    void findByRecordId_returnsLedger() {
        SyncedLedgerRecord r = new SyncedLedgerRecord();
        r.setId(20L); r.setRecordId(10L); r.setInspectionDate(LocalDate.of(2026,7,7));
        r.setContent("台账内容"); r.setSyncVersion(5L);
        ledgerMapper.upsert(r);

        SyncedLedgerRecord found = ledgerMapper.findByRecordId(10L);
        assertThat(found).isNotNull();
        assertThat(found.getContent()).isEqualTo("台账内容");
    }
}
