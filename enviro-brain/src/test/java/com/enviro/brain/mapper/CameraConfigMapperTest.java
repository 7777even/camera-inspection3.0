package com.enviro.brain.mapper;

import com.enviro.brain.entity.CameraConfig;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@ActiveProfiles("test")
class CameraConfigMapperTest {

    @Autowired
    private CameraConfigMapper mapper;

    @Test
    void insert_shouldGenerateId() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-CFG-001");
        config.setCameraName("配置测试摄像头");
        config.setRtspUrl("rtsp://192.168.1.100:554/stream");
        config.setLocation("一楼大厅");
        config.setEnabled(1);

        mapper.insert(config);

        assertThat(config.getId()).isNotNull();
        assertThat(config.getId()).isGreaterThan(0);
    }

    @Test
    void findById_shouldReturnConfig() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-CFG-002");
        config.setCameraName("查找测试");
        config.setRtspUrl("rtsp://192.168.1.101:554/stream");
        config.setLocation("二楼办公室");
        config.setEnabled(1);
        mapper.insert(config);

        CameraConfig found = mapper.findById(config.getId());

        assertThat(found).isNotNull();
        assertThat(found.getCameraCode()).isEqualTo("CAM-CFG-002");
        assertThat(found.getCameraName()).isEqualTo("查找测试");
    }

    @Test
    void findByCameraCode_shouldReturnConfig() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-UNIQUE-001");
        config.setCameraName("唯一编码测试");
        config.setRtspUrl("rtsp://192.168.1.102:554/stream");
        config.setEnabled(1);
        mapper.insert(config);

        CameraConfig found = mapper.findByCameraCode("CAM-UNIQUE-001");

        assertThat(found).isNotNull();
        assertThat(found.getCameraCode()).isEqualTo("CAM-UNIQUE-001");
    }

    @Test
    void findAll_shouldReturnAllConfigs() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-FINDALL");
        config.setCameraName("findAll测试");
        config.setRtspUrl("rtsp://192.168.1.103:554/stream");
        config.setEnabled(1);
        mapper.insert(config);

        List<CameraConfig> results = mapper.findAll();

        assertThat(results).isNotEmpty();
    }

    @Test
    void upsert_shouldInsertWhenNotExists() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-UPSERT-001");
        config.setCameraName("Upsert新增");
        config.setRtspUrl("rtsp://192.168.1.200:554/stream");
        config.setLocation("三楼会议室");
        config.setEnabled(1);

        int rows = mapper.upsert(config);

        assertThat(rows).isEqualTo(1);

        CameraConfig found = mapper.findByCameraCode("CAM-UPSERT-001");
        assertThat(found).isNotNull();
        assertThat(found.getCameraName()).isEqualTo("Upsert新增");
    }

    @Test
    void upsert_shouldUpdateWhenExists() {
        // First insert
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-UPSERT-002");
        config.setCameraName("Upsert原始");
        config.setRtspUrl("rtsp://192.168.1.201:554/stream");
        config.setLocation("四楼走廊");
        config.setEnabled(1);
        mapper.insert(config);

        // Then upsert with same cameraCode
        CameraConfig update = new CameraConfig();
        update.setCameraCode("CAM-UPSERT-002");
        update.setCameraName("Upsert更新后");
        update.setRtspUrl("rtsp://192.168.1.201:554/new-stream");
        update.setLocation("四楼走廊-新位置");
        update.setEnabled(0);

        int rows = mapper.upsert(update);

        assertThat(rows).isEqualTo(1);

        CameraConfig found = mapper.findByCameraCode("CAM-UPSERT-002");
        assertThat(found).isNotNull();
        assertThat(found.getCameraName()).isEqualTo("Upsert更新后");
        assertThat(found.getEnabled()).isEqualTo(0);
    }

    @Test
    void deleteByCameraCode_shouldDelete() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-DELETE-001");
        config.setCameraName("待删除");
        config.setRtspUrl("rtsp://192.168.1.300:554/stream");
        config.setEnabled(1);
        mapper.insert(config);

        int rows = mapper.deleteByCameraCode("CAM-DELETE-001");

        assertThat(rows).isEqualTo(1);

        CameraConfig found = mapper.findByCameraCode("CAM-DELETE-001");
        assertThat(found).isNull();
    }

    @Test
    void update_shouldUpdateFields() {
        CameraConfig config = new CameraConfig();
        config.setCameraCode("CAM-UPDATE-001");
        config.setCameraName("更新前");
        config.setRtspUrl("rtsp://192.168.1.400:554/stream");
        config.setLocation("五楼");
        config.setEnabled(1);
        mapper.insert(config);

        config.setCameraName("更新后");
        config.setLocation("五楼-新位置");
        config.setEnabled(0);

        int rows = mapper.update(config);

        assertThat(rows).isEqualTo(1);

        CameraConfig found = mapper.findByCameraCode("CAM-UPDATE-001");
        assertThat(found.getCameraName()).isEqualTo("更新后");
        assertThat(found.getEnabled()).isEqualTo(0);
    }
}
