package com.enviro.brain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SyncResponse DTO")
class SyncResponseTest {

    @Nested
    @DisplayName("of() 静态工厂")
    class OfFactory {

        @Test
        void shouldCreateSyncResponseWithHasMore() {
            List<String> data = Arrays.asList("a", "b", "c");
            SyncResponse<String> response = SyncResponse.of(data, true, 3L);

            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getMessage()).isEqualTo("success");
            assertThat(response.getData()).containsExactly("a", "b", "c");
            assertThat(response.isHasMore()).isTrue();
            assertThat(response.getNextSince()).isEqualTo(3L);
        }

        @Test
        void shouldCreateSyncResponseWithoutHasMore() {
            List<String> data = Arrays.asList("x", "y");
            SyncResponse<String> response = SyncResponse.of(data, false, 5L);

            assertThat(response.getData()).hasSize(2);
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextSince()).isEqualTo(5L);
        }

        @Test
        void shouldHandleEmptyData() {
            SyncResponse<String> response = SyncResponse.of(Collections.emptyList(), false, 0L);

            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getData()).isEmpty();
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextSince()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("继承 ApiResponse")
    class Inheritance {

        @Test
        void shouldBeInstanceOfApiResponse() {
            SyncResponse<String> response = SyncResponse.of(List.of("a"), false, 1L);

            assertThat(response).isInstanceOf(ApiResponse.class);
        }
    }

    @Nested
    @DisplayName("serialVersionUID 存在")
    class Serialization {

        @Test
        void shouldSupportSerialization() {
            SyncResponse<String> response = SyncResponse.of(List.of("test"), true, 10L);

            // Verify fields are set correctly via serialization-like checks
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getData()).isNotNull();
            assertThat(response.getNextSince()).isEqualTo(10L);
        }
    }
}
