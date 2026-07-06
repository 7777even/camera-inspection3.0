package com.enviro.brain.service;

import com.enviro.brain.mapper.SyncVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncVersionService")
class SyncVersionServiceTest {

    @Mock
    private SyncVersionMapper syncVersionMapper;

    @InjectMocks
    private SyncVersionService syncVersionService;

    @Nested
    @DisplayName("nextVersion()")
    class NextVersion {

        @Test
        void shouldReturnNextVersion() {
            when(syncVersionMapper.nextVersion()).thenReturn(42L);

            Long result = syncVersionService.nextVersion();

            assertThat(result).isEqualTo(42L);
            verify(syncVersionMapper, times(1)).nextVersion();
        }

        @Test
        void shouldDelegateToMapper() {
            when(syncVersionMapper.nextVersion()).thenReturn(1L);

            syncVersionService.nextVersion();

            verify(syncVersionMapper).nextVersion();
        }
    }

    @Nested
    @DisplayName("getWatermark()")
    class GetWatermark {

        @Test
        @DisplayName("should return current version minus one as watermark")
        void shouldReturnCurrentVersionMinusOne() {
            when(syncVersionMapper.getCurrentVersion()).thenReturn(10L);

            Long watermark = syncVersionService.getWatermark();

            assertThat(watermark).isEqualTo(9L);
            verify(syncVersionMapper).getCurrentVersion();
        }

        @Test
        @DisplayName("should return zero when current version is one")
        void shouldReturnZeroWhenCurrentVersionIsOne() {
            when(syncVersionMapper.getCurrentVersion()).thenReturn(1L);

            Long watermark = syncVersionService.getWatermark();

            assertThat(watermark).isZero();
        }
    }
}
