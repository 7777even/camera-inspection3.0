package com.queqiao.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 鹊桥数据同步层启动入口。
 * 负责从环保小脑定时增量同步数据到鹊桥自有数据库。
 */
@SpringBootApplication
@EnableScheduling
public class QueqiaoSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueqiaoSyncApplication.class, args);
    }
}
