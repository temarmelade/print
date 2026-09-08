package com.printkiosk.client.service;

import com.printkiosk.shared.api.dto.AdCreativeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Локальный кеш рекламных роликов.
 *
 * <p>Существует по двум причинам.
 *
 * <p><b>Заголовки.</b> JavaFX {@code Media} и {@code Image} скачивают файл
 * сами и наших заголовков не отправляют, а эндпоинт закрыт ключом киоска.
 * Поэтому файл забирается обычным клиентом, а плееру отдаётся путь на диске.
 *
 * <p><b>Сеть.</b> Заставка крутит одни и те же ролики по кругу. Тянуть
 * видео из сети на каждом повторе по вайфаю торгового центра — это рывки
 * при воспроизведении и лишний трафик.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdMediaCache {

    private final com.printkiosk.client.api.KioskServerClient server;

    private final Path cacheDir =
            Path.of(System.getProperty("java.io.tmpdir"), "kiosk-ads");

    /**
     * Гарантирует, что все ролики плейлиста лежат на диске, и убирает
     * файлы, которых в плейлисте больше нет.
     *
     * <p>Вызывается из фонового обновления плейлиста, не из UI-потока:
     * скачивание может занять секунды.
     */
    public void sync(List<AdCreativeDto> playlist) {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.warn("Не удалось создать папку кеша рекламы: {}", e.toString());
            return;
        }

        Set<String> expected = new HashSet<>();

        for (AdCreativeDto ad : playlist) {
            String name = fileName(ad);
            expected.add(name);
            Path target = cacheDir.resolve(name);

            if (Files.isRegularFile(target)) continue;   // уже скачан

            try {
                byte[] bytes = server.downloadAdMedia(ad.id());
                if (bytes == null || bytes.length == 0) {
                    log.warn("Ролик {} пришёл пустым — пропускаем", ad.id());
                    continue;
                }
                // Пишем во временный файл и переименовываем: иначе заставка
                // может подхватить наполовину скачанный файл и показать
                // битое видео.
                Path tmp = cacheDir.resolve(name + ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Ролик закеширован: {} ({} КБ)", name, bytes.length / 1024);

            } catch (Exception e) {
                log.warn("Не удалось скачать ролик {}: {}", ad.id(), e.toString());
            }
        }

        cleanup(expected);
    }

    /**
     * Локальный путь ролика для плеера или {@code null}, если файла нет.
     * Проигрывать сетевой адрес как запасной вариант нельзя — он вернёт
     * 403 без ключа.
     */
    public String localUri(AdCreativeDto ad) {
        Path file = cacheDir.resolve(fileName(ad));
        return Files.isRegularFile(file) ? file.toUri().toString() : null;
    }

    /** Удаляет ролики, которых больше нет в плейлисте. */
    private void cleanup(Set<String> expected) {
        try (var files = Files.list(cacheDir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> !expected.contains(p.getFileName().toString()))
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                         log.info("Удалён неактуальный ролик: {}", p.getFileName());
                     } catch (IOException ignored) {
                         // файл мог быть занят плеером — уберём в следующий раз
                     }
                 });
        } catch (IOException e) {
            log.debug("Чистка кеша рекламы не удалась: {}", e.toString());
        }
    }

    /**
     * Имя файла включает id ролика. Расширение важно: JavaFX выбирает
     * декодер по нему, и для видео без .mp4 воспроизведение не начнётся.
     */
    private static String fileName(AdCreativeDto ad) {
        String ext = switch (ad.mediaType()) {
            case VIDEO -> ".mp4";
            // Расширение берём из contentType: для GIF важно сохранить
            // .gif, иначе анимация не заведётся, а по .img формат
            // определяется не всегда.
            case IMAGE -> switch (ad.contentType() == null ? "" : ad.contentType()) {
                case "image/gif"  -> ".gif";
                case "image/png"  -> ".png";
                default            -> ".jpg";
            };
        };
        return ad.id() + ext;
    }
}
