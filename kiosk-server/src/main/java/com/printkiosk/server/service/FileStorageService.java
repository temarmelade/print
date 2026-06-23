package com.printkiosk.server.service;

import com.printkiosk.server.config.KioskServerProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Управление файлами в Docker volume.
 * <p>
 * Файл сохраняется атомарно (через .tmp + ATOMIC_MOVE), чтобы Nginx
 * не успел отдать неполный файл, если киоск запросит его в момент
 * записи. Все обращения к файлам проходят через resolveSafe() —
 * это защита от path traversal в случае, если когда-то имя файла
 * придёт извне (сейчас оно генерируется как UUIDv7, но защита остаётся).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private final KioskServerProperties properties;

    private Path storageRoot;

    @PostConstruct
    void init() throws IOException {
        this.storageRoot = Paths.get(properties.getStorage().getPath())
                .toAbsolutePath()
                .normalize();

        if (!Files.exists(storageRoot)) {
            Files.createDirectories(storageRoot);
            log.info("Created storage directory: {}", storageRoot);
        }
        if (!Files.isDirectory(storageRoot)) {
            throw new IllegalStateException(
                    "Storage path is not a directory: " + storageRoot);
        }
        if (!Files.isWritable(storageRoot)) {
            throw new IllegalStateException(
                    "Storage path is not writable: " + storageRoot);
        }

        log.info("FileStorageService initialized at {}", storageRoot);
    }

    /**
     * Сохраняет загруженный файл под указанным именем.
     * Сначала пишет в {@code <name>.tmp}, затем атомарно переименовывает —
     * это гарантирует, что в директории никогда не появится полу-файл.
     */
    public void save(MultipartFile source, String storedFilename) throws IOException {
        Path target = resolveSafe(storedFilename);
        Path tmp    = target.resolveSibling(storedFilename + ".tmp");

        try (var in = source.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Запасной путь для ФС без поддержки atomic move (редкость в Linux).
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        log.debug("Saved file {} ({} bytes)", storedFilename, source.getSize());
    }

    /**
     * Удаляет файл, если он существует. Никогда не бросает исключения —
     * это нужно cleanup-джобу, который не должен падать из-за одного
     * пропавшего файла. Все ошибки логируются как WARN.
     */
    public void deleteQuietly(String storedFilename) {
        try {
            Path target = resolveSafe(storedFilename);
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.debug("Deleted file {}", storedFilename);
            }
        } catch (Exception e) {
            log.warn("Failed to delete file {}: {}", storedFilename, e.getMessage());
        }
    }

    public boolean exists(String storedFilename) {
        try {
            return Files.exists(resolveSafe(storedFilename));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Абсолютный путь к файлу — нужен для диагностики/админ-эндпоинтов. */
    public Path resolve(String storedFilename) {
        return resolveSafe(storedFilename);
    }

    /**
     * Резолвит относительное имя в абсолютный путь и проверяет,
     * что результат не вышел за пределы storageRoot.
     */
    private Path resolveSafe(String storedFilename) {
        if (storedFilename == null || storedFilename.isBlank()) {
            throw new IllegalArgumentException("Stored filename must not be blank");
        }
        if (storedFilename.contains("/")
                || storedFilename.contains("\\")
                || storedFilename.contains("..")) {
            throw new IllegalArgumentException("Invalid stored filename: " + storedFilename);
        }

        Path resolved = storageRoot.resolve(storedFilename).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException(
                    "Resolved path escapes storage root: " + storedFilename);
        }
        return resolved;
    }
}
