package com.printkiosk.server.service;

import com.printkiosk.server.domain.FileEntity;
import com.printkiosk.server.domain.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiredFileCleanupJob {

    /**
     * Grace-период: файл удаляется только если истёк > 30s назад.
     * Это защищает киоск, который начал скачивание прямо в момент
     * истечения TTL: у него есть 30 секунд закончить download.
     */
    private static final Duration GRACE = Duration.ofSeconds(30);

    private final FileRepository fileRepository;
    private final FileStorageService storage;

    @Scheduled(fixedDelayString = "${kiosk.cleanup.interval-ms:60000}")
    @Transactional
    public void purgeExpired() {
        Instant threshold = Instant.now().minus(GRACE);

        List<FileEntity> expired = fileRepository.findExpiredBefore(threshold);
        if (expired.isEmpty()) return;

        // Сначала диск — он операционно "дороже" БД (медленнее восстанавливается).
        // Если упадём между шагами 1 и 2, следующий запуск джоба корректно
        // повторит удаление БД-строки (delete на отсутствующий файл — idempotent).
        for (FileEntity f : expired) {
            storage.deleteQuietly(f.getStoredFilename());
        }
        int rows = fileRepository.deleteExpiredBefore(threshold);

        log.info("Cleanup: purged {} expired files (grace={})", rows, GRACE);
    }
}
