package com.printkiosk.server.config;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    /**
     * Токен и имя не помечены @NotBlank намеренно: при выключенном боте
     * (enabled=false) их незачем задавать, а валидация роняла бы старт
     * приложения. Наличие токена проверяется при регистрации бота.
     */
    private String token = "";
    private String username = "";
    private boolean enabled = true;          // на проде включён, локально можно выключить

    /**
     * Код доступа к уведомлениям об инцидентах. Бот общий для клиентов и
     * персонала, поэтому подписка на служебные оповещения закрыта кодом.
     * Пустое значение = подписка отключена полностью.
     */
    private String alertsToken = "";
}
