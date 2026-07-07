package com.queqiao.sync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 归一化 MyBatis 的 databaseId，使同一 Mapper 能同时支持生产 MySQL 与测试 H2 两套方言。
 *
 * <p>生产环境使用 {@code ON DUPLICATE KEY UPDATE} 做幂等 upsert；
 * H2（测试）不支持该语法，使用 {@code MERGE INTO}。Mapper XML 中为同一条 statement 提供
 * {@code databaseId="h2"} 与默认（无 databaseId，对应 MySQL）两个版本，由本 Provider 解析出的
 * databaseId 决定选用哪一个。
 */
@Configuration
public class DatabaseConfig {

    @Bean
    public org.apache.ibatis.mapping.DatabaseIdProvider databaseIdProvider() {
        return (DataSource dataSource) -> {
            try (Connection conn = dataSource.getConnection()) {
                String name = conn.getMetaData().getDatabaseProductName().toLowerCase();
                if (name.contains("h2")) {
                    return "h2";
                }
                if (name.contains("mysql")) {
                    return "mysql";
                }
                return name;
            } catch (SQLException e) {
                return null;
            }
        };
    }
}
