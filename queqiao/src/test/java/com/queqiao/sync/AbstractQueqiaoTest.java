package com.queqiao.sync;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 鹊桥同步层所有 Spring 测试的共用基类。
 *
 * <p>关键职责：为每个测试上下文分配<b>独立的 H2 内存库</b>（通过 {@link DynamicPropertySource}
 * 生成唯一库名），避免多个测试上下文共用同一内存库导致的两类问题：
 * <ol>
 *   <li>第二个上下文再执行 {@code schema-h2.sql} 时报 {@code Table already exists}，
 *       进而触发 Spring 的 ApplicationContext failure-threshold 连锁失败；</li>
 *   <li>跨上下文数据串味（例如服务测试写入的水位被映射测试读到，破坏断言）。</li>
 * </ol>
 *
 * <p>同时强制 {@code Replace.NONE} 使用 MySQL 兼容模式的 H2（支持 {@code ON DUPLICATE KEY UPDATE}），
 * 并激活 {@code test} profile 以加载 {@code application-test.yml}。
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractQueqiaoTest {

    private static final AtomicInteger CONTEXT_SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void isolatedH2Database(DynamicPropertyRegistry registry) {
        String dbName = "queqiao_test_" + CONTEXT_SEQ.incrementAndGet();
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:" + dbName
                        + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
    }
}
