package com.enviro.brain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiResponse DTO")
class ApiResponseTest {

    @Nested
    @DisplayName("success() 静态工厂")
    class SuccessFactory {

        @Test
        void shouldCreateResponseWithCode200() {
            ApiResponse<String> response = ApiResponse.success("hello");

            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getMessage()).isEqualTo("success");
            assertThat(response.getData()).isEqualTo("hello");
        }

        @Test
        void shouldCreateResponseWithoutData() {
            ApiResponse<Void> response = ApiResponse.success();

            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getMessage()).isEqualTo("success");
            assertThat(response.getData()).isNull();
        }
    }

    @Nested
    @DisplayName("error() 静态工厂")
    class ErrorFactory {

        @Test
        void shouldCreateErrorResponse() {
            ApiResponse<Void> response = ApiResponse.error(400, "Bad Request");

            assertThat(response.getCode()).isEqualTo(400);
            assertThat(response.getMessage()).isEqualTo("Bad Request");
            assertThat(response.getData()).isNull();
        }
    }

    @Nested
    @DisplayName("泛型正确性")
    class GenericCorrectness {

        @Test
        void shouldPreserveGenericType() {
            ApiResponse<Integer> response = ApiResponse.success(42);

            assertThat(response.getData()).isEqualTo(42);
            assertThat(response).isInstanceOf(ApiResponse.class);
        }
    }
}
