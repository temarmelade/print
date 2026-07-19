package com.printkiosk.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    private final ServerProperties properties;

    @Bean
    public ObjectMapper kioskObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    public RestClient kioskServerRestClient(ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        var jacksonConverter = new MappingJackson2HttpMessageConverter(mapper);

        // Для multipart-загрузки файлов (сканы/ксерокопия) нужен
        // FormHttpMessageConverter. Он, в свою очередь, использует
        // Resource/ByteArray-конвертеры для частей-файлов, а для JSON-частей —
        // наш Jackson. Без этого POST multipart падает: «нет конвертера».
        var formConverter = new org.springframework.http.converter.FormHttpMessageConverter();
        formConverter.addPartConverter(jacksonConverter);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("X-Kiosk-Id", properties.getKioskId())
                .defaultHeader("X-Kiosk-Key", properties.getApiKey())
                .defaultHeaders(headers -> {
                    if (properties.getAuthToken() != null && !properties.getAuthToken().isBlank()) {
                        headers.setBearerAuth(properties.getAuthToken());
                    }
                })
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());
                    converters.add(new org.springframework.http.converter.ResourceHttpMessageConverter());
                    converters.add(formConverter);
                    converters.add(jacksonConverter);
                })
                .build();
    }
}