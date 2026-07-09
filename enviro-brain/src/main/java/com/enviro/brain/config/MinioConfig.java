package com.enviro.brain.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MinIO 客户端配置。
 * 连接信息来自 {@code enviro.minio.*}（见 application.yml），支持环境变量覆盖。
 */
@Configuration
@EnableScheduling
public class MinioConfig {

    @Bean
    public MinioClient minioClient(@Value("${enviro.minio.endpoint}") String endpoint,
                                    @Value("${enviro.minio.access-key}") String accessKey,
                                    @Value("${enviro.minio.secret-key}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
