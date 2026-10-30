package com.qinghuan.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ItWorkbenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItWorkbenchApplication.class, args);
    }
}
