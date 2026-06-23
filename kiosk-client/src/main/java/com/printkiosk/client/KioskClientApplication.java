package com.printkiosk.client;

import com.printkiosk.client.config.KioskClientProperties;
import javafx.application.Application;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(KioskClientProperties.class)
@ConfigurationPropertiesScan("com.printkiosk.client.config")
public class KioskClientApplication {

    public static void main(String[] args) {
        Application.launch(KioskJavaFxApp.class, args);
    }
}