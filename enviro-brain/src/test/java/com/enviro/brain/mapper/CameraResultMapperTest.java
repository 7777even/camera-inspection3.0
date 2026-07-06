package com.enviro.brain.mapper;

import com.enviro.brain.entity.CameraResult;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@ActiveProfiles("test")
class CameraResultMapperTest {

    @Autowired
    private CameraResultMapper mapper;

    @Test
    void insert_shouldGenerateId() {
        CameraResult result = new CameraResult();
        result.setRecordId(1L);
        result.setCameraCode("CAM-001");
        result.setCameraName("摄像头001");
        result.setStatus("ONLINE");
        result.setQualityScore(new BigDecimal("85.50"));
        result.setScreenshotPath("/screenshots/CAM-001-001.jpg");
        result.setErrorMessage(null);

        mapper.insert(result);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getId()).isGreaterThan(0);
    }

    @Test
    void findById_shouldReturnResult() {
        CameraResult result = new CameraResult();
        result.setRecordId(1L);
        result.setCameraCode("CAM-002");
        result.setCameraName("摄像头002");
        result.setStatus("OFFLINE");
        result.setQualityScore(new BigDecimal("0.00"));
        result.setErrorMessage("Connection timeout");
        mapper.insert(result);

        CameraResult found = mapper.findById(result.getId());

        assertThat(found).isNotNull();
        assertThat(found.getCameraCode()).isEqualTo("CAM-002");
        assertThat(found.getStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void findByRecordId_shouldReturnResults() {
        CameraResult result1 = new CameraResult();
        result1.setRecordId(999L);
        result1.setCameraCode("CAM-003");
        result1.setCameraName("摄像头003");
        result1.setStatus("ONLINE");
        mapper.insert(result1);

        CameraResult result2 = new CameraResult();
        result2.setRecordId(999L);
        result2.setCameraCode("CAM-004");
        result2.setCameraName("摄像头004");
        result2.setStatus("ABNORMAL");
        mapper.insert(result2);

        List<CameraResult> results = mapper.findByRecordId(999L);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results).allMatch(r -> r.getRecordId().equals(999L));
    }

    @Test
    void findBySyncVersionGreaterThan_shouldReturnNewerResults() {
        List<CameraResult> results = mapper.findBySyncVersionGreaterThan(0L, 100);
        // Should only return results with sync_version > 0
        assertThat(results).allMatch(r -> r.getSyncVersion() != null && r.getSyncVersion() > 0);
    }

    @Test
    void findAll_shouldReturnAllResults() {
        CameraResult result = new CameraResult();
        result.setRecordId(1L);
        result.setCameraCode("CAM-ALL");
        result.setCameraName("摄像头ALL");
        result.setStatus("ONLINE");
        mapper.insert(result);

        List<CameraResult> results = mapper.findAll();

        assertThat(results).isNotEmpty();
    }
}
