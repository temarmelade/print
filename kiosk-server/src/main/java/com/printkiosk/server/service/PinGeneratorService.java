package com.printkiosk.server.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.printkiosk.server.domain.FileEntity;
import com.printkiosk.server.domain.FileRepository;
import com.printkiosk.server.exception.PinGenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class PinGeneratorService {

    private static final int MAX_ATTEMPTS = 16;
    private static final int CODE_SPACE = 10_000;   // 0000..9999

    private final FileRepository fileRepository;

    /*
     * Подбирает 4-значный PIN, гарантированно непересекающийся
     * с активными (не истёкшими и не использованными) кодами.
     *
     * Возможные коллизии при параллельной вставке отлавливаются
     * на уровне UNIQUE-индекса в БД (см. FileService.upload).
     */
    public String pickUnusedPin() {
        var rng = ThreadLocalRandom.current();
        Instant now = Instant.now();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String candidate = String.format("%04d", rng.nextInt(CODE_SPACE));

            if (!fileRepository.existsActiveByCode(candidate, now)) {
                return candidate;
            }
            log.debug("PIN collision (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, candidate);
        }

        // 16 промахов подряд при кодпространстве 10к означает,
        // что активно занято > 30–50% PIN'ов — это уже алерт.
        throw new PinGenerationException(
                "Could not generate unique PIN after " + MAX_ATTEMPTS + " attempts");
    }
}