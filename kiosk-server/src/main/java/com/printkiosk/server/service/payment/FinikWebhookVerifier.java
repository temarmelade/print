package com.printkiosk.server.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Верифицирует входящие webhook-запросы от Finik по их публичному ключу.
 * <p>
 * Алгоритм описан в {@link FinikCanonicalizer}. Дополнительно проверяется
 * timestamp на свежесть (anti-replay).
 */
@Slf4j
@Service
public class FinikWebhookVerifier {

    /** Максимальная разница между x-api-timestamp и текущим временем сервера. */
    private static final Duration MAX_TIMESTAMP_SKEW = Duration.ofMinutes(5);

    private static final String SIGNATURE_HEADER = "signature";
    private static final String TIMESTAMP_HEADER = "x-api-timestamp";
    private static final String API_HEADER_PREFIX = "x-api-";

    private final FinikPublicKeyProvider publicKeyProvider;
    private final FinikCanonicalizer canonicalizer;

    @Autowired
    public FinikWebhookVerifier(FinikPublicKeyProvider publicKeyProvider,
                                ObjectMapper objectMapper) {
        this.publicKeyProvider = publicKeyProvider;
        this.canonicalizer = new FinikCanonicalizer(objectMapper);
    }

    /**
     * Проверяет подпись и свежесть webhook'а.
     *
     * @return {@code true} если запрос валиден, {@code false} в любом случае ошибки
     *         (детали — в логах, наружу не пробрасываем, чтобы не давать атакующему
     *         информацию о причинах отказа).
     */
    public boolean verify(HttpServletRequest request, String rawBody) {
        try {
            String signatureHeader = request.getHeader(SIGNATURE_HEADER);
            if (signatureHeader == null || signatureHeader.isBlank()) {
                log.warn("Webhook: missing 'signature' header");
                return false;
            }
            signatureHeader = signatureHeader.trim();

            if (!isTimestampFresh(request)) {
                return false;
            }

            FinikSignedRequest signed = snapshot(request, rawBody, signatureHeader);
            String canonical = canonicalizer.build(signed);

            boolean valid = verifySignature(canonical, signatureHeader);
            if (valid) {
                log.info("Webhook signature OK (path={}, ts={})",
                        request.getRequestURI(), request.getHeader(TIMESTAMP_HEADER));
            } else {
                log.warn("Webhook signature INVALID (path={}, sig.len={})",
                        request.getRequestURI(), signatureHeader.length());
                if (log.isDebugEnabled()) {
                    log.debug("Canonical payload:\n{}", canonical);
                }
            }
            return valid;

        } catch (FinikSignatureException e) {
            log.warn("Webhook rejected: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Webhook verification error", e);
            return false;
        }
    }

    private boolean isTimestampFresh(HttpServletRequest request) {
        String ts = request.getHeader(TIMESTAMP_HEADER);
        if (ts == null || ts.isBlank()) {
            log.warn("Webhook: missing '{}' header", TIMESTAMP_HEADER);
            return false;
        }
        long parsed;
        try {
            parsed = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            log.warn("Webhook: invalid '{}' format: {}", TIMESTAMP_HEADER, ts);
            return false;
        }
        long now = System.currentTimeMillis();
        long delta = Math.abs(now - parsed);
        if (delta > MAX_TIMESTAMP_SKEW.toMillis()) {
            log.warn("Webhook: timestamp skew {}ms exceeds limit {}ms",
                    delta, MAX_TIMESTAMP_SKEW.toMillis());
            return false;
        }
        return true;
    }

    private FinikSignedRequest snapshot(HttpServletRequest request,
                                        String rawBody,
                                        String signature) {
        Map<String, String> apiHeaders = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(API_HEADER_PREFIX)) {
                apiHeaders.put(lower, request.getHeader(name));
            }
        }

        Long timestampMs = null;
        String ts = request.getHeader(TIMESTAMP_HEADER);
        if (ts != null && !ts.isBlank()) {
            try { timestampMs = Long.parseLong(ts.trim()); }
            catch (NumberFormatException ignored) {}
        }

        return new FinikSignedRequest(
                request.getMethod(),
                request.getRequestURI(),
                request.getHeader("Host"),
                apiHeaders,
                request.getQueryString(),
                rawBody,
                signature,
                timestampMs);
    }

    private boolean verifySignature(String canonical, String signatureB64) {
        try {
            byte[] sig = Base64.getDecoder().decode(signatureB64);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKeyProvider.getPublicKey());
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return signature.verify(sig);
        } catch (IllegalArgumentException e) {
            // Base64 decode failed
            log.warn("Webhook: signature is not valid Base64");
            return false;
        } catch (Exception e) {
            log.warn("Webhook: signature verification threw", e);
            return false;
        }
    }
}