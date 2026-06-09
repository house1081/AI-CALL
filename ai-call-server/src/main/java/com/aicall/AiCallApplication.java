package com.aicall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.aicall.mapper")
@EnableScheduling
public class AiCallApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiCallApplication.class, args);
    }
}
