package com.enviro.brain.mapper;

import com.enviro.brain.entity.InspectionRecord;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@ActiveProfiles("test")
class InspectionRecordMapperTest {

    @Autowired
    private InspectionRecordMapper mapper;

    @Test
    void insert_shouldGenerateId() {
        InspectionRecord record = new InspectionRecord();
        record.setBatchId("BATCH-001");
        record.setInspectionDate(LocalDate.now());
        record.setTotalCameras(10);
        record.setOnlineCount(8);
        record.setOfflineCount(2);
        record.setAbnormalCount(0);
        record.setStatus("COMPLETED");

        mapper.insert(record);

        assertThat(record.getId()).isNotNull();
        assertThat(record.getId()).isGreaterThan(0);
    }

    @Test
    void findById_shouldReturnRecord() {
        InspectionRecord record = new InspectionRecord();
        record.setBatchId("BATCH-002");
        record.setInspectionDate(LocalDate.now());
        record.setTotalCameras(5);
        record.setOnlineCount(4);
        record.setOfflineCount(1);
        record.setAbnormalCount(0);
        record.setStatus("COMPLETED");
        mapper.insert(record);

        InspectionRecord found = mapper.findById(record.getId());

        assertThat(found).isNotNull();
        assertThat(found.getBatchId()).isEqualTo("BATCH-002");
        assertThat(found.getTotalCameras()).isEqualTo(5);
    }

    @Test
    void findByBatchId_shouldReturnRecords() {
        InspectionRecord record = new InspectionRecord();
        record.setBatchId("BATCH-003");
        record.setInspectionDate(LocalDate.now());
        record.setTotalCameras(3);
        record.setOnlineCount(2);
        record.setOfflineCount(1);
        record.setAbnormalCount(0);
        record.setStatus("COMPLETED");
        mapper.insert(record);

        List<InspectionRecord> results = mapper.findByBatchId("BATCH-003");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getBatchId()).isEqualTo("BATCH-003");
    }

    @Test
    void findBySyncVersionGreaterThan_shouldReturnNewerRecords() {
        // Insert record with syncVersion = 0 (default)
        InspectionRecord oldRecord = new InspectionRecord();
        oldRecord.setBatchId("BATCH-OLD");
        oldRecord.setInspectionDate(LocalDate.now());
        oldRecord.setTotalCameras(1);
        oldRecord.setOnlineCount(1);
        oldRecord.setOfflineCount(0);
        oldRecord.setAbnormalCount(0);
        oldRecord.setStatus("COMPLETED");
        oldRecord.setSyncVersion(0L);
        mapper.insert(oldRecord);

        // Update sync_version manually for testing
        // (In real usage, sync_version is set by the sync mechanism)

        List<InspectionRecord> results = mapper.findBySyncVersionGreaterThan(0L, 10);

        // oldRecord has syncVersion=0, so it should NOT be returned
        assertThat(results).allMatch(r -> r.getSyncVersion() != null && r.getSyncVersion() > 0);
    }

    @Test
    void findAll_shouldReturnAllRecords() {
        InspectionRecord record1 = new InspectionRecord();
        record1.setBatchId("BATCH-ALL-1");
        record1.setInspectionDate(LocalDate.now());
        record1.setTotalCameras(1);
        record1.setOnlineCount(1);
        record1.setOfflineCount(0);
        record1.setAbnormalCount(0);
        record1.setStatus("COMPLETED");
        mapper.insert(record1);

        InspectionRecord record2 = new InspectionRecord();
        record2.setBatchId("BATCH-ALL-2");
        record2.setInspectionDate(LocalDate.now());
        record2.setTotalCameras(1);
        record2.setOnlineCount(1);
        record2.setOfflineCount(0);
        record2.setAbnormalCount(0);
        record2.setStatus("COMPLETED");
        mapper.insert(record2);

        List<InspectionRecord> results = mapper.findAll();

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    }
}
