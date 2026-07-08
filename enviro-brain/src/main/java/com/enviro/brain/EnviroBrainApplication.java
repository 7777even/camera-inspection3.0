package com.enviro.brain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enviro Brain 摄像头巡检系统启动类
 */
@SpringBootApplication
@EnableAsync
public class EnviroBrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnviroBrainApplication.class, args);
    }
}
