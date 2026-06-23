package com.printkiosk.server.service.payment;

import com.printkiosk.server.config.FinikProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * Предоставляет публичный ключ Finik для верификации webhook'ов.
 * Ключ загружается один раз при старте, дальше отдаётся без блокировок.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinikPublicKeyProvider {

    /** Beta-окружение. Источник: Finik docs (Payments Status Webhook). */
    private static final String BETA_PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwlrlKz/8gLWd1ARWGA/8
            o3a3Qy8G+hPifyqiPosiTY6nCHovANMIJXk6DH4qAqqZeLu8pLGxudkPbv8dSyG7
            F9PZEAryMPzjoB/9P/F6g0W46K/FHDtwTM3YIVvstbEbL19m8yddv/xCT9JPPJTb
            LsSTVZq5zCqvKzpupwlGS3Q3oPyLAYe+ZUn4Bx2J1WQrBu3b08fNaR3E8pAkCK27
            JqFnP0eFfa817VCtyVKcFHb5ij/D0eUP519Qr/pgn+gsoG63W4pPHN/pKwQUUiAy
            uLSHqL5S2yu1dffyMcMVi9E/Q2HCTcez5OvOllgOtkNYHSv9pnrMRuws3u87+hNT
            ZwIDAQAB
            -----END PUBLIC KEY-----
            """;

    /** Prod-окружение. Источник: Finik docs. */
    private static final String PROD_PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuF/PUmhMPPidcMxhZBPb
            BSGJoSphmCI+h6ru8fG8guAlcPMVlhs+ThTjw2LHABvciwtpj51ebJ4EqhlySPyT
            hqSfXI6Jp5dPGJNDguxfocohaz98wvT+WAF86DEglZ8dEsfoumojFUy5sTOBdHEu
            g94B4BbrJvjmBa1YIx9Azse4HFlWhzZoYPgyQpArhokeHOHIN2QFzJqeriANO+wV
            aUMta2AhRVZHbfyJ36XPhGO6A5FYQWgjzkI65cxZs5LaNFmRx6pjnhjIeVKKgF99
            4OoYCzhuR9QmWkPl7tL4Kd68qa/xHLz0Psnuhm0CStWOYUu3J7ZpzRK8GoEXRcr8
            tQIDAQAB
            -----END PUBLIC KEY-----
            """;

    private final FinikProperties properties;
    private PublicKey publicKey;            // immutable после @PostConstruct

    @PostConstruct
    void init() {
        String env = properties.getEnvironment();
        String pem = "prod".equalsIgnoreCase(env) ? PROD_PUBLIC_KEY : BETA_PUBLIC_KEY;

        log.info("Loading Finik public key for environment: {}",
                env == null ? "beta (default)" : env.toLowerCase(Locale.ROOT));

        try {
            this.publicKey = parsePublicKey(pem);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Finik public key", e);
        }
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decoded));
    }
}