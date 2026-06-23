package com.printkiosk.server.integration.telegram;

import com.printkiosk.server.config.TelegramBotProperties;
import com.printkiosk.server.service.FileService;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.UploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;

import java.io.InputStream;
import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TelegramPrintBot extends TelegramLongPollingBot {

    private final TelegramBotProperties properties;
    private final FileService fileService;
    private final BotMessages messages;

    /** Хранилище выбранного языка пользователя в памяти (per-instance). */
    private final Map<Long, String> userLangs = new ConcurrentHashMap<>();

    public TelegramPrintBot(TelegramBotProperties properties,
                            @Lazy FileService fileService,
                            BotMessages messages) {
        this.properties = properties;
        this.fileService = fileService;
        this.messages = messages;
    }

    @Override public String getBotToken()    { return properties.getToken(); }
    @Override public String getBotUsername() { return properties.getUsername(); }

    // ════════════════════════════════════════════════════════════════
    //  Update dispatch
    // ════════════════════════════════════════════════════════════════

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) return;
        Message message = update.getMessage();
        Long chatId = message.getChatId();

        try {
            if (message.hasText())     { handleText(chatId, message.getText()); return; }
            if (message.hasDocument()) { handleDocument(chatId, message.getDocument()); return; }
            if (message.hasPhoto())    { handlePhoto(chatId, message.getPhoto()); return; }

            sendText(chatId, messages.unsupportedContent(lang(chatId)));

        } catch (Exception e) {
            log.error("Error processing Telegram update from chat {}", chatId, e);
            sendText(chatId, messages.genericError(lang(chatId)));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Text commands
    // ════════════════════════════════════════════════════════════════

    private void handleText(Long chatId, String rawText) {
        String text = rawText == null ? "" : rawText.trim().toLowerCase();

        if (text.equals("/ru") || text.equals("/kg") || text.equals("/en")) {
            userLangs.put(chatId, text.substring(1));
            sendText(chatId, messages.languageChanged(text.substring(1)));
            return;
        }

        if (text.equals("/start")) {
            sendText(chatId, messages.welcome());
            return;
        }

        sendText(chatId, messages.howToUse(lang(chatId)));
    }

    // ════════════════════════════════════════════════════════════════
    //  Document & photo uploads
    // ════════════════════════════════════════════════════════════════

    private void handleDocument(Long chatId, Document document) {
        sendText(chatId, messages.uploading(lang(chatId)));

        try {
            UploadResponse response = downloadAndUpload(
                    document.getFileId(),
                    document.getFileSize(),
                    document.getMimeType(),
                    document.getFileName(),
                    chatId
            );
            sendText(chatId, messages.uploadSuccess(lang(chatId), response.pin()), true);
        } catch (Exception e) {
            log.warn("Document upload failed for chat {}: {}", chatId, e.getMessage());
            sendText(chatId, messages.uploadFailed(lang(chatId)));
        }
    }

    private void handlePhoto(Long chatId, List<PhotoSize> photos) {
        if (photos == null || photos.isEmpty()) {
            sendText(chatId, messages.uploadFailed(lang(chatId)));
            return;
        }

        PhotoSize best = photos.stream()
                .max(Comparator.comparing(PhotoSize::getFileSize))
                .orElseThrow(() -> new IllegalStateException("Empty photo list"));

        sendText(chatId, messages.uploading(lang(chatId)));

        try {
            UploadResponse response = downloadAndUpload(
                    best.getFileId(),
                    best.getFileSize().longValue(),
                    "image/jpeg",
                    "telegram_photo.jpg",
                    chatId
            );
            sendText(chatId, messages.uploadSuccess(lang(chatId), response.pin()), true);
        } catch (Exception e) {
            log.warn("Photo upload failed for chat {}", chatId, e);
            sendText(chatId, messages.uploadFailed(lang(chatId)));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Core upload helper
    // ════════════════════════════════════════════════════════════════

    private UploadResponse downloadAndUpload(String telegramFileId,
                                             long expectedSize,
                                             String mimeType,
                                             String originalName,
                                             Long chatId) throws Exception {

        GetFile getFile = new GetFile(telegramFileId);
        String filePath = execute(getFile).getFilePath();
        URL fileUrl = new URL("https://api.telegram.org/file/bot"
                + properties.getToken() + "/" + filePath);

        try (InputStream in = fileUrl.openStream()) {
            return fileService.upload(
                    in,
                    expectedSize,
                    mimeType,
                    originalName,
                    UploadSource.TELEGRAM,
                    chatId
            );
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════

    private String lang(Long chatId) {
        return userLangs.getOrDefault(chatId, "ru");
    }

    private void sendText(Long chatId, String text) {
        sendText(chatId, text, false);
    }

    private void sendText(Long chatId, String text, boolean markdown) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        if (markdown) msg.setParseMode("Markdown");
        try {
            execute(msg);
        } catch (Exception e) {
            log.warn("Failed to send message to chat {}: {}", chatId, e.getMessage());
        }
    }
}