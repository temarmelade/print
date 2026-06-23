package com.printkiosk.server.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {
    @NotBlank private String token;
    @NotBlank private String username;
    private boolean enabled = true;          // на проде включён, локально можно выключить
}
