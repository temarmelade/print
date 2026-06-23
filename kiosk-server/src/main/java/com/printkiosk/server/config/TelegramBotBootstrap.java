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

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
            log.info("Telegram bot registered: @{}", bot.getBotUsername());
        } catch (Exception e) {
            log.error("Failed to register Telegram bot", e);
        }
    }
}
