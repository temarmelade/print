package com.printkiosk.server.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Канонизация запроса для подписи/верификации по алгоритму Finik.
 * <p>
 * Алгоритм:
 * <pre>
 *   data  = lowercase(method) + "\n"
 *   data += path              + "\n"
 *   data += canonical-headers + "\n"
 *   data += canonical-query   + "\n"   (только если query непустой)
 *   data += body              ← root keys sorted, nested untouched
 * </pre>
 *
 * Особенность: Finik сортирует ТОЛЬКО корневые ключи JSON. Вложенные объекты
 * остаются в порядке вставки. Это совпадает с реализацией через
 * {@code Object.keys().sort() + JSON.stringify()} в JavaScript.
 */
@RequiredArgsConstructor
class FinikCanonicalizer {

    private final ObjectMapper objectMapper;

    String build(FinikSignedRequest req) {
        StringBuilder out = new StringBuilder(512);

        out.append(req.method().toLowerCase(Locale.ROOT)).append('\n');
        out.append(req.path()).append('\n');
        appendCanonicalHeaders(out, req);
        out.append('\n');

        String canonicalQuery = canonicalQuery(req.queryString());
        if (!canonicalQuery.isEmpty()) {
            out.append(canonicalQuery).append('\n');
        }

        out.append(canonicalBody(req.rawBody()));
        return out.toString();
    }

    // ── headers ─────────────────────────────────────────────────────

    private void appendCanonicalHeaders(StringBuilder out, FinikSignedRequest req) {
        SortedMap<String, String> sorted = new TreeMap<>();
        sorted.put("host", req.host() == null ? "" : req.host());
        req.apiHeaders().forEach((k, v) ->
                sorted.put(k.toLowerCase(Locale.ROOT), v == null ? "" : v));

        boolean first = true;
        for (var e : sorted.entrySet()) {
            if (!first) out.append('&');
            out.append(e.getKey()).append(':').append(e.getValue());
            first = false;
        }
    }

    // ── query ───────────────────────────────────────────────────────

    private String canonicalQuery(String raw) {
        if (raw == null || raw.isBlank()) return "";

        SortedMap<String, String> sorted = new TreeMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            // Декодируем raw query (могут быть %20, %D0% и т.д.), потом
            // encode по нашей канонической схеме. Иначе двойное encoding.
            sorted.put(decode(k), decode(v));
        }

        StringBuilder out = new StringBuilder(64);
        boolean first = true;
        for (var e : sorted.entrySet()) {
            if (!first) out.append('&');
            out.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return out.toString();
    }

    private String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    // ── body ────────────────────────────────────────────────────────

    /**
     * Канонизация: только корневые ключи сортируются. Вложенные узлы
     * сериализуются как есть.
     */
    private String canonicalBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (!root.isObject()) {
                return objectMapper.writeValueAsString(root);
            }

            // LinkedHashMap → сохраняет порядок вставки, мы вставляем
            // в отсортированном порядке.
            List<String> keys = new ArrayList<>();
            root.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);

            ObjectNode sorted = objectMapper.createObjectNode();
            for (String k : keys) {
                sorted.set(k, root.get(k));  // вложенные узлы НЕ трогаем
            }
            return objectMapper.writeValueAsString(sorted);

        } catch (Exception e) {
            throw new FinikSignatureException("Cannot parse webhook body as JSON", e);
        }
    }
}
