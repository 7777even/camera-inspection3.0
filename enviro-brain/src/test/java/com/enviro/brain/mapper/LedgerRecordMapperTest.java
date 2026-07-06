package com.enviro.brain.mapper;

import com.enviro.brain.entity.LedgerRecord;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@ActiveProfiles("test")
class LedgerRecordMapperTest {

    @Autowired
    private LedgerRecordMapper mapper;

    @Test
    void insert_shouldGenerateId() {
        LedgerRecord record = new LedgerRecord();
        record.setRecordId(1L);
        record.setInspectionDate(LocalDate.now());
        record.setContent("测试台账内容");
        record.setDocxPath("/docs/ledger-001.docx");

        mapper.insert(record);

        assertThat(record.getId()).isNotNull();
        assertThat(record.getId()).isGreaterThan(0);
    }

    @Test
    void findById_shouldReturnRecord() {
        LedgerRecord record = new LedgerRecord();
        record.setRecordId(1L);
        record.setInspectionDate(LocalDate.now());
        record.setContent("测试查找功能");
        record.setDocxPath("/docs/ledger-find.docx");
        mapper.insert(record);

        LedgerRecord found = mapper.findById(record.getId());

        assertThat(found).isNotNull();
        assertThat(found.getContent()).isEqualTo("测试查找功能");
    }

    @Test
    void findByRecordId_shouldReturnRecords() {
        LedgerRecord record1 = new LedgerRecord();
        record1.setRecordId(888L);
        record1.setInspectionDate(LocalDate.now());
        record1.setContent("台账记录1");
        mapper.insert(record1);

        LedgerRecord record2 = new LedgerRecord();
        record2.setRecordId(888L);
        record2.setInspectionDate(LocalDate.now());
        record2.setContent("台账记录2");
        mapper.insert(record2);

        List<LedgerRecord> results = mapper.findByRecordId(888L);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results).allMatch(r -> r.getRecordId().equals(888L));
    }

    @Test
    void findBySyncVersionGreaterThan_shouldReturnNewerRecords() {
        List<LedgerRecord> results = mapper.findBySyncVersionGreaterThan(0L, 100);
        assertThat(results).allMatch(r -> r.getSyncVersion() != null && r.getSyncVersion() > 0);
    }

    @Test
    void findAll_shouldReturnAllRecords() {
        LedgerRecord record = new LedgerRecord();
        record.setRecordId(1L);
        record.setInspectionDate(LocalDate.now());
        record.setContent("findAll测试");
        mapper.insert(record);

        List<LedgerRecord> results = mapper.findAll();

        assertThat(results).isNotEmpty();
    }
}
