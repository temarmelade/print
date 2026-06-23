package com.printkiosk.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.util.List;

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

        HttpMessageConverter<?> jacksonConverter = new MappingJackson2HttpMessageConverter(mapper);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("X-Kiosk-Id", properties.getKioskId())
                .defaultHeaders(headers -> {
                    if (properties.getAuthToken() != null && !properties.getAuthToken().isBlank()) {
                        headers.setBearerAuth(properties.getAuthToken());
                    }
                })
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(jacksonConverter);
                })
                .build();
    }
}