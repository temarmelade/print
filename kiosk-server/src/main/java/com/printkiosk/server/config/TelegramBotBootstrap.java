package com.printkiosk.server.config;

import com.printkiosk.server.integration.telegram.TelegramPrintBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBotBootstrap {

    private final TelegramPrintBot bot;
    private final TelegramBotProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        // Флаг раньше не проверялся: локально бот пытался подключиться с пустым
        // токеном и сыпал ошибками в лог при каждом старте.
        if (!properties.isEnabled()) {
            log.info("Telegram bot отключён (telegram.bot.enabled=false)");
            return;
        }
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            log.error("Telegram bot включён, но TELEGRAM_BOT_TOKEN не задан — бот не запущен");
            return;
        }
        try {
            new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
            log.info("Telegram bot registered: @{}", bot.getBotUsername());
        } catch (Exception e) {
            log.error("Failed to register Telegram bot", e);
        }
    }
}
