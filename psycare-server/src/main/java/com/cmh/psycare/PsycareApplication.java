package com.cmh.psycare;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 社区心理健康评测与问诊系统启动类
 */
@EnableAsync
@MapperScan("com.cmh.psycare.mapper")
@SpringBootApplication
public class PsycareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PsycareApplication.class, args);
    }
}
