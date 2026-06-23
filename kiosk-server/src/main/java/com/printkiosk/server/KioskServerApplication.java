package com.printkiosk.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("com.printkiosk.server.config")
@EnableScheduling
public class KioskServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KioskServerApplication.class, args);
    }
}