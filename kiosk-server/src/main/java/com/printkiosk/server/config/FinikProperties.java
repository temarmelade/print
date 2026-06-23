package com.printkiosk.server.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "finik")
public class FinikProperties {

    /** Включён ли реальный Finik. На local-профиле false → используется MockFinikPaymentGateway. */
    private boolean enabled = false;

    /** {@code beta} | {@code prod}. Управляет выбором публичного ключа для верификации webhook. */
    private String environment = "prod";

    private String baseUrl;
    private String apiKey;
    private String privateKeyPath;
    private String accountId;

    private String redirectUrl;
    private String webhookUrl;

    private String merchantCategoryCode = "0742";
    private String qrName = "PrintKiosk";
}