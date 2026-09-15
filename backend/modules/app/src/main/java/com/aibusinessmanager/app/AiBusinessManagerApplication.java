package com.aibusinessmanager.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.aibusinessmanager")
@EnableScheduling
public class AiBusinessManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiBusinessManagerApplication.class, args);
    }
}
