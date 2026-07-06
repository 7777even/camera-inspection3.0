package com.enviro.brain.mapper;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@ActiveProfiles("test")
class SyncVersionMapperTest {

    @Autowired
    private SyncVersionMapper mapper;

    @Test
    void nextVersion_shouldIncrementAtomically() {
        // First call
        Long v1 = mapper.nextVersion();
        assertThat(v1).isEqualTo(1L);

        // Second call
        Long v2 = mapper.nextVersion();
        assertThat(v2).isEqualTo(2L);

        // Third call
        Long v3 = mapper.nextVersion();
        assertThat(v3).isEqualTo(3L);
    }

    @Test
    void nextVersion_shouldBeMonotonicallyIncreasing() {
        // Call multiple times and verify monotonic increase
        Long prev = 0L;
        for (int i = 0; i < 10; i++) {
            Long current = mapper.nextVersion();
            assertThat(current).isGreaterThan(prev);
            prev = current;
        }
    }
}
