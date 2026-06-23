package com.printkiosk.server.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.printkiosk.server.config.FinikProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinikPaymentGateway implements PaymentGateway {

    private static final String CREATE_PAYMENT_PATH = "/v1/payment";

    private final FinikProperties finikProperties;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Override
    public GatewayPaymentResult createPayment(String orderId, int amountSom) {
        if (!finikProperties.isEnabled()) {
            throw new IllegalStateException("Finik payment is disabled");
        }
        if (amountSom <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than 0");
        }

        try {
            String paymentId = UUID.randomUUID().toString();
            String timestamp = String.valueOf(Instant.now().toEpochMilli());

            Map<String, Object> data = new TreeMap<>();
            data.put("accountId", finikProperties.getAccountId());
            data.put("merchantCategoryCode", finikProperties.getMerchantCategoryCode());
            data.put("name_en", finikProperties.getQrName());
            data.put("webhookUrl", finikProperties.getWebhookUrl());
            data.put("description", "Print Kiosk order " + orderId);

            List<Map<String, Object>> additionalData = new ArrayList<>();
            Map<String, Object> orderField = new TreeMap<>();
            orderField.put("fieldId", "orderId");
            orderField.put("name", "Order ID");
            orderField.put("isHidden", true);
            orderField.put("value", orderId);
            additionalData.add(orderField);
            data.put("additionalData", additionalData);

            Map<String, Object> body = new TreeMap<>();
            body.put("Amount", amountSom);
            body.put("CardType", "FINIK_QR");
            body.put("PaymentId", paymentId);
            body.put("RedirectUrl", finikProperties.getRedirectUrl());
            body.put("Data", data);

            URI baseUri = URI.create(finikProperties.getBaseUrl());
            String host = baseUri.getHost();

            Map<String, String> signingHeaders = new TreeMap<>();
            signingHeaders.put("host", host);
            signingHeaders.put("x-api-key", finikProperties.getApiKey());
            signingHeaders.put("x-api-timestamp", timestamp);

            String bodyJson = objectMapper.writeValueAsString(body);

            String canonicalPayload = buildCanonicalPayload(
                    "POST", CREATE_PAYMENT_PATH, signingHeaders, null, bodyJson);

            String signature = sign(canonicalPayload, finikProperties.getPrivateKeyPath());

            URI requestUri = URI.create(finikProperties.getBaseUrl() + CREATE_PAYMENT_PATH);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(requestUri)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", finikProperties.getApiKey())
                    .header("x-api-timestamp", timestamp)
                    .header("signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int statusCode = response.statusCode();
            log.info("Finik create payment status: {}", statusCode);

            if (statusCode == 302 || statusCode == 301 || statusCode == 303
                    || statusCode == 307 || statusCode == 308) {
                String paymentUrl = response.headers()
                        .firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException(
                                "Finik returned redirect without Location header"));
                log.info("Finik payment URL created: {}", paymentUrl);
                return new GatewayPaymentResult(paymentId, paymentUrl, amountSom, "PENDING");
            }

            if (statusCode == 201 || statusCode == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String paymentUrl = json.hasNonNull("paymentUrl")
                        ? json.get("paymentUrl").asText() : null;
                String responsePaymentId = json.hasNonNull("paymentId")
                        ? json.get("paymentId").asText() : paymentId;
                return new GatewayPaymentResult(responsePaymentId, paymentUrl, amountSom, "PENDING");
            }

            throw new IllegalStateException(
                    "Finik create payment failed. Status: " + statusCode
                            + ", body: " + response.body()
                            + ", diagnostic-headers: " + extractDiagnosticHeaders(response));

        } catch (Exception e) {
            log.error("Could not create Finik payment", e);
            throw new RuntimeException("Не удалось создать оплату Finik: " + e.getMessage(), e);
        }
    }

    private String extractDiagnosticHeaders(HttpResponse<String> response) {
        StringBuilder sb = new StringBuilder("{");
        String[] interesting = {
                "x-amzn-trace-id", "x-amzn-requestid", "x-amz-apigw-id",
                "x-amz-cf-id", "x-cache", "date"
        };
        boolean first = true;
        for (String name : interesting) {
            Optional<String> value = response.headers().firstValue(name);
            if (value.isPresent()) {
                if (!first) sb.append(", ");
                sb.append(name).append("=").append(value.get());
                first = false;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildCanonicalPayload(String method, String path,
                                         Map<String, String> headers,
                                         Map<String, String> queryParams,
                                         String bodyJson) {
        StringBuilder data = new StringBuilder();
        data.append(method.toLowerCase()).append("\n");
        data.append(path).append("\n");
        data.append(buildCanonicalHeaders(headers)).append("\n");
        if (queryParams != null && !queryParams.isEmpty()) {
            data.append(buildCanonicalQuery(queryParams)).append("\n");
        }
        data.append(bodyJson);
        return data.toString();
    }

    private String buildCanonicalHeaders(Map<String, String> headers) {
        TreeMap<String, String> sorted = new TreeMap<>();
        headers.forEach((key, value) -> {
            String lowerKey = key.toLowerCase();
            if ("host".equals(lowerKey) || lowerKey.startsWith("x-api-")) {
                sorted.put(lowerKey, value);
            }
        });
        StringJoiner joiner = new StringJoiner("&");
        sorted.forEach((key, value) -> joiner.add(key + ":" + value));
        return joiner.toString();
    }

    private String buildCanonicalQuery(Map<String, String> queryParams) {
        TreeMap<String, String> sorted = new TreeMap<>(queryParams);
        StringJoiner joiner = new StringJoiner("&");
        sorted.forEach((key, value) ->
                joiner.add(urlEncode(key) + "=" + urlEncode(value == null ? "" : value)));
        return joiner.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String sign(String payload, String privateKeyPath) throws Exception {
        RSAPrivateKey privateKey = loadPrivateKey(privateKeyPath);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private RSAPrivateKey loadPrivateKey(String privateKeyPath) throws Exception {
        String pem = Files.readString(Path.of(privateKeyPath));
        byte[] pkcs8Bytes;

        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            String base64 = pem
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] pkcs1Bytes = Base64.getDecoder().decode(base64);
            pkcs8Bytes = wrapPkcs1InPkcs8(pkcs1Bytes);
        } else {
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            pkcs8Bytes = Base64.getDecoder().decode(base64);
        }

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] algorithmId = new byte[] {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        byte[] version = new byte[] {0x02, 0x01, 0x00};
        byte[] privateKeyOctetString = derEncode((byte) 0x04, pkcs1);
        byte[] inner = concat(version, algorithmId, privateKeyOctetString);
        return derEncode((byte) 0x30, inner);
    }

    private static byte[] derEncode(byte tag, byte[] content) {
        byte[] lengthBytes = derLength(content.length);
        byte[] out = new byte[1 + lengthBytes.length + content.length];
        out[0] = tag;
        System.arraycopy(lengthBytes, 0, out, 1, lengthBytes.length);
        System.arraycopy(content, 0, out, 1 + lengthBytes.length, content.length);
        return out;
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) return new byte[] {(byte) length};
        int byteCount = 0;
        int temp = length;
        while (temp > 0) { byteCount++; temp >>= 8; }
        byte[] out = new byte[1 + byteCount];
        out[0] = (byte) (0x80 | byteCount);
        for (int i = byteCount; i >= 1; i--) {
            out[i] = (byte) (length & 0xff);
            length >>= 8;
        }
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }
}