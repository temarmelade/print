//package com.printkiosk.server.service.payment;
//
//import com.printkiosk.server.config.FinikProperties;
//import lombok.RequiredArgsConstructor;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.http.client.SimpleClientHttpRequestFactory;
//import org.springframework.web.client.RestClient;
//
//import java.time.Duration;
//
//@Configuration
//@RequiredArgsConstructor
//class FinikConfig {
//
//    private final FinikProperties properties;
//
//    @Bean
//    RestClient finikRestClient() {
//        var factory = new SimpleClientHttpRequestFactory();
//        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
//        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
//
//        return RestClient.builder()
//                .baseUrl(properties.getBaseUrl())
//                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
//                .requestFactory(factory)
//                .build();
//    }
//}