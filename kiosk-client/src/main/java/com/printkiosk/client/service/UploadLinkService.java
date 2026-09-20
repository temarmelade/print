package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.config.KioskClientProperties;
import com.printkiosk.client.config.ServerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Ссылка на веб-страницу загрузки для QR-кода на экране UPLOAD.
 *
 * <p>Источник истины — сервер ({@code GET /api/kiosk/upload-link}): только он
 * знает, по какому адресу его видит телефон посетителя. Раньше ссылка жила
 * в конфиге каждого киоска и по умолчанию была {@code http://192.168.1.120/upload}
 * — без порта и с IP, который давно не совпадал с реальным.
 *
 * <p>Пока ответа сервера нет (старт, обрыв сети, не выдан ключ киоска), QR
 * строится из запасного адреса, а как только ссылка приходит — экран
 * перерисовывает QR через слушатель.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadLinkService {

    /** Сколько полученная ссылка считается свежей. */
    private static final Duration REFRESH_AFTER = Duration.ofMinutes(10);
    /** Потолок паузы между повторами при недоступном сервере. */
    private static final long MAX_BACKOFF_SEC = 600;

    private final KioskServerClient server;
    private final ServerProperties serverProperties;
    private final KioskClientProperties clientProperties;

    private volatile String serverLink;                 // null — ещё не получена
    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private volatile Runnable listener = () -> {};
    private int failures;                               // только поток планировщика
    private boolean fallbackWarned;

    /**
     * Вызывается при смене ссылки — из фонового потока. Один слушатель,
     * как у остальных flow-сервисов: MainController переподписывается при
     * пересоздании, а не копит подписки.
     */
    public void setListener(Runnable listener) {
        this.listener = listener != null ? listener : () -> {};
    }

    /** Ссылка без параметра языка. Никогда не null. */
    public String currentLink() {
        String link = serverLink;
        return link != null ? link : fallbackLink();
    }

    @Scheduled(initialDelayString = "${kiosk.upload.link-initial-delay-ms:1500}",
               fixedDelayString   = "${kiosk.upload.link-check-ms:15000}")
    public void refreshIfDue() {
        Instant now = Instant.now();
        if (now.isBefore(nextAttemptAt)) return;

        try {
            var response = server.uploadLink();
            String link = response != null ? response.url() : null;
            if (link == null || link.isBlank()) {
                throw new IllegalStateException("сервер вернул пустую ссылку");
            }
            failures = 0;
            nextAttemptAt = now.plus(REFRESH_AFTER);
            if (!link.equals(serverLink)) {
                serverLink = link;
                log.info("Ссылка для QR загрузки: {}", link);
                listener.run();
            }
        } catch (Exception e) {
            failures++;
            long backoff = Math.min(MAX_BACKOFF_SEC, 15L << Math.min(failures, 6));
            nextAttemptAt = now.plusSeconds(backoff);
            // Уже полученную ссылку не сбрасываем: адрес сервера меняется
            // редко, а короткий обрыв не повод перерисовывать QR на запасной.
            if (serverLink == null) {
                warnFallbackOnce(e);
            } else {
                log.debug("Не удалось обновить ссылку загрузки: {}", e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Запасной адрес
    // ════════════════════════════════════════════════════════════════

    /**
     * Пока сервер не ответил: явная {@code kiosk.upload.web-url}, если
     * задана, иначе {@code kiosk.server.public-base-url} + /upload.
     */
    private String fallbackLink() {
        String manual = clientProperties.getUpload().getWebUrl();
        if (manual != null && !manual.isBlank()) return manual.trim();

        String base = serverProperties.getPublicBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/upload?k="
                + URLEncoder.encode(serverProperties.getKioskId(), StandardCharsets.UTF_8);
    }

    private void warnFallbackOnce(Exception e) {
        if (fallbackWarned) return;
        fallbackWarned = true;
        String fallback = fallbackLink();
        if (isLocalOnly(fallback)) {
            log.warn("Сервер не выдал ссылку загрузки ({}), а запасной адрес {} "
                    + "указывает на эту машину — телефон его не откроет. Проверьте "
                    + "kiosk.server.api-key или задайте kiosk.server.public-base-url.",
                    e.getMessage(), fallback);
        } else {
            log.warn("Сервер не выдал ссылку загрузки ({}), QR ведёт на запасной адрес {}",
                    e.getMessage(), fallback);
        }
    }

    private static boolean isLocalOnly(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return true;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.startsWith("127.");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
