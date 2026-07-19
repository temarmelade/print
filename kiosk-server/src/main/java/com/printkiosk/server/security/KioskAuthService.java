package com.printkiosk.server.security;

import com.printkiosk.server.domain.KioskEntity;
import com.printkiosk.server.domain.KioskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Аутентификация киосков по API-ключу.
 *
 * <p>До этого киоск «представлялся» лишь заголовком X-Kiosk-Id — то есть любой
 * человек в сети мог выдать себя за киоск и слать фальшивую телеметрию или
 * помечать чужие задания выполненными. Теперь нужен ещё и секретный ключ,
 * который хранится только в виде bcrypt-хеша.
 */
@Service
@RequiredArgsConstructor
public class KioskAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KioskRepository kiosks;
    private final PasswordEncoder encoder;

    /** Проверяет пару (kioskId, apiKey). Пустой ключ — сразу мимо. */
    @Transactional(readOnly = true)
    public Optional<KioskEntity> authenticate(String kioskId, String apiKey) {
        if (kioskId == null || apiKey == null || apiKey.isBlank()) return Optional.empty();

        return kiosks.findById(kioskId)
                .filter(KioskEntity::isEnabled)
                .filter(k -> encoder.matches(apiKey, k.getApiKeyHash()));
    }

    /** Генерирует новый ключ. Возвращается ОДИН раз — в базе только хеш. */
    public String generateApiKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String apiKey) {
        return encoder.encode(apiKey);
    }
}
