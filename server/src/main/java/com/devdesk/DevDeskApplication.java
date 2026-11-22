package com.devdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DevDeskApplication {
    public static void main(String[] args) { SpringApplication.run(DevDeskApplication.class, args); }
}
