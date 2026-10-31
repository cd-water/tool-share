package com.cdwater.toolshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ToolShareApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolShareApplication.class, args);
    }

}
