package com.queqiao.sync.mapper;

import com.queqiao.sync.AbstractQueqiaoTest;
import com.queqiao.sync.entity.SyncWatermark;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
class SyncWatermarkMapperTest extends AbstractQueqiaoTest {

    @Autowired
    private SyncWatermarkMapper mapper;

    @Test
    void selectByTableName_shouldReturnNullWhenAbsent() {
        assertThat(mapper.selectByTableName("inspections")).isNull();
    }

    @Test
    void upsert_shouldInsertAndBeIdempotent() {
        SyncWatermark w = new SyncWatermark();
        w.setTableName("inspections");
        w.setLastSyncVersion(50L);
        w.setLastSyncTime(LocalDateTime.now());

        mapper.upsert(w);
        SyncWatermark first = mapper.selectByTableName("inspections");
        assertThat(first).isNotNull();
        assertThat(first.getLastSyncVersion()).isEqualTo(50L);

        w.setLastSyncVersion(120L);
        mapper.upsert(w);
        SyncWatermark second = mapper.selectByTableName("inspections");
        assertThat(second.getLastSyncVersion()).isEqualTo(120L);
        // 仍为同一行（按 table_name 唯一），无重复
        assertThat(mapper.selectByTableName("inspections")).isEqualTo(second);
    }
}
