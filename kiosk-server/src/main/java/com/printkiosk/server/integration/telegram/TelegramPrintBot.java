package com.printkiosk.server.integration.telegram;

import com.printkiosk.server.config.TelegramBotProperties;
import com.printkiosk.server.service.FileService;
import com.printkiosk.server.service.IncidentService;
import com.printkiosk.server.service.incident.IncidentMessageFormatter;
import com.printkiosk.server.service.incident.IncidentSubscriptionService;
import com.printkiosk.shared.api.IncidentSeverity;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.UploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import com.printkiosk.server.domain.FileEntity;
import com.printkiosk.server.exception.PinNotFoundException;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
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
    private final IncidentSubscriptionService subscriptions;
    private final IncidentMessageFormatter incidentMessages;
    private final IncidentService incidents;

    /** Токен скана в ссылке get_<токен> — тот же формат, что у веб-ссылки. */
    private static final java.util.regex.Pattern SCAN_TOKEN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{20,64}");

    /** Хранилище выбранного языка пользователя в памяти (per-instance). */
    private final Map<Long, String> userLangs = new ConcurrentHashMap<>();

    public TelegramPrintBot(TelegramBotProperties properties,
                            @Lazy FileService fileService,
                            BotMessages messages,
                            @Lazy IncidentSubscriptionService subscriptions,
                            IncidentMessageFormatter incidentMessages,
                            @Lazy IncidentService incidents) {
        this.properties = properties;
        this.fileService = fileService;
        this.messages = messages;
        this.subscriptions = subscriptions;
        this.incidentMessages = incidentMessages;
        this.incidents = incidents;
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
        String text = rawText == null ? "" : rawText.trim();
        String lower = text.toLowerCase();

        // ── Служебные команды персонала ──
        // Разбираем ДО остальных: они начинаются с /alerts и /status и не
        // должны попасть в клиентскую ветку «как пользоваться».
        if (lower.startsWith("/alerts")) { handleAlerts(chatId, text); return; }
        if (lower.equals("/status"))     { handleStatus(chatId); return; }

        if (lower.equals("/ru") || lower.equals("/kg") || lower.equals("/en")) {
            userLangs.put(chatId, lower.substring(1));
            sendText(chatId, messages.languageChanged(lower.substring(1)));
            return;
        }

        // Deep-link из QR киоска приходит как «/start <параметр>». Раньше
        // здесь было equals("/start"), и «/start get_…» уходил в «как
        // пользоваться» — оплаченный скан бот не отправлял никогда.
        if (lower.equals("/start") || lower.startsWith("/start ")) {
            handleStart(chatId, text.substring("/start".length()).trim());
            return;
        }

        sendText(chatId, messages.howToUse(lang(chatId)));
    }

    /**
     * {@code /start} с параметром из QR-кода киоска:
     * <ul>
     *   <li>{@code get_<токен>} — получение оплаченного скана;</li>
     *   <li>{@code lang_ru|ky|en} — QR загрузки файла, выставляем язык.</li>
     * </ul>
     * Параметр берётся из исходного текста: токен регистрозависимый.
     */
    private void handleStart(Long chatId, String payload) {
        if (payload.startsWith("get_")) {
            deliverScan(chatId, payload.substring("get_".length()));
            return;
        }
        if (payload.startsWith("lang_")) {
            String lang = normalizeLang(payload.substring("lang_".length()));
            if (lang != null) userLangs.put(chatId, lang);
        }
        sendText(chatId, messages.welcome());
    }

    /**
     * Отправляет оплаченный скан документом. Ссылку можно открыть повторно
     * (например, с другого телефона) — пока скан не истёк, бот пришлёт его
     * снова: доставка оплачена, а токен знает только тот, кто видел QR.
     */
    private void deliverScan(Long chatId, String token) {
        String lang = lang(chatId);
        if (!SCAN_TOKEN.matcher(token).matches()) {
            sendText(chatId, messages.scanUnavailable(lang));
            return;
        }

        FileEntity file;
        try {
            file = fileService.getPaidScanForDelivery(token);
        } catch (PinNotFoundException e) {
            sendText(chatId, messages.scanUnavailable(lang));
            return;
        }

        java.io.File onDisk = fileService.storedPath(file).toFile();
        if (!onDisk.isFile()) {
            log.warn("Scan delivery: file id={} missing on disk", file.getId());
            sendText(chatId, messages.scanUnavailable(lang));
            return;
        }

        SendDocument doc = new SendDocument();
        doc.setChatId(chatId.toString());
        doc.setDocument(new InputFile(onDisk, file.getOriginalFilename()));
        doc.setCaption(messages.scanDelivered(lang));
        try {
            execute(doc);
            log.info("Scan delivered via Telegram: file id={} chat={}", file.getId(), chatId);
        } catch (Exception e) {
            log.error("Scan delivery via Telegram failed: file id={}", file.getId(), e);
            sendText(chatId, messages.genericError(lang));
        }
    }

    /** Код языка из QR киоска → код бота. Киоск шлёт «ky», бот хранит «kg». */
    private static String normalizeLang(String code) {
        return switch (code.toLowerCase()) {
            case "ru"       -> "ru";
            case "ky", "kg" -> "kg";
            case "en"       -> "en";
            default         -> null;
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  Служебные уведомления (персонал)
    // ════════════════════════════════════════════════════════════════

    /**
     * {@code /alerts <код>} — подписаться, {@code /alerts all} — включить и
     * предупреждения, {@code /alerts off} — отписаться.
     *
     * <p>Подписка закрыта кодом: бот общий с клиентами, и посторонним незачем
     * знать о состоянии сети киосков.
     */
    private void handleAlerts(Long chatId, String text) {
        String[] parts = text.trim().split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1].trim() : "";

        if (arg.equalsIgnoreCase("off")) {
            boolean was = subscriptions.unsubscribe(chatId);
            sendText(chatId, was ? incidentMessages.unsubscribed()
                                 : incidentMessages.notSubscribed());
            return;
        }

        // Смена фильтра доступна только уже подписанным — код повторно не просим.
        if (arg.equalsIgnoreCase("all") || arg.equalsIgnoreCase("down")) {
            IncidentSeverity level = arg.equalsIgnoreCase("all")
                    ? IncidentSeverity.WARNING : IncidentSeverity.DOWN;
            if (subscriptions.changeSeverity(chatId, level)) {
                sendText(chatId, incidentMessages.subscribed(level));
            } else {
                sendText(chatId, incidentMessages.notSubscribed());
            }
            return;
        }

        if (!subscriptions.isValidToken(arg)) {
            sendText(chatId, incidentMessages.accessDenied());
            return;
        }

        subscriptions.subscribe(chatId, null, IncidentSeverity.DOWN);
        sendText(chatId, incidentMessages.subscribed(IncidentSeverity.DOWN));
    }

    /** {@code /status} — что сломано прямо сейчас. Только для подписчиков. */
    private void handleStatus(Long chatId) {
        if (!subscriptions.isSubscribed(chatId)) {
            sendText(chatId, incidentMessages.notSubscribed());
            return;
        }

        var open = incidents.openIncidents();
        if (open.isEmpty()) {
            sendText(chatId, incidentMessages.allClear());
            return;
        }

        StringBuilder sb = new StringBuilder("Открытые инциденты:\n\n");
        open.forEach(i -> sb.append(incidentMessages.statusLine(
                i.kioskName(), i.incidentType(), i.severity(), i.durationMinutes()))
                .append('\n'));
        sendText(chatId, sb.toString());
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

    /**
     * Отправка служебного уведомления. В отличие от {@link #sendText}, ошибку
     * НЕ проглатывает: вызывающему нужно знать, что чат недоступен, чтобы
     * погасить мёртвую подписку.
     */
    public void sendNotification(Long chatId, String text) throws Exception {
        if (!properties.isEnabled()) {
            log.debug("Бот отключён — уведомление для {} не отправлено", chatId);
            return;
        }
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setDisableWebPagePreview(true);
        execute(msg);
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