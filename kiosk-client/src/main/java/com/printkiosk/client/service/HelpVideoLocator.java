package com.printkiosk.client.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.Optional;

/**
 * Ищет видеоинструкцию для раздела помощи.
 *
 * <h2>Почему два места поиска</h2>
 * Сначала проверяется папка на диске киоска, и только потом ресурс внутри
 * jar. Это позволяет заменить ролик, просто положив новый файл на машину, —
 * без пересборки и передеплоя. Ролики внутри jar остаются как запасной
 * вариант «из коробки».
 */
@Slf4j
@Component
public class HelpVideoLocator {

    /**
     * Папка с видео на киоске. Файлы: {@code help_print.mp4},
     * {@code help_copy.mp4}, {@code help_scan.mp4}.
     */
    @Value("${kiosk.help.video-dir:C:/PrintKiosk/videos}")
    private String videoDir;

    /**
     * @param topic {@code print} | {@code copy} | {@code scan}
     * @return URL для MediaPlayer или пусто, если ролика нет ни на диске,
     *         ни в jar
     */
    public Optional<String> find(String topic) {
        String fileName = "help_" + topic + ".mp4";

        // 1. Диск киоска — приоритетнее, чтобы ролик можно было подменить.
        try {
            File external = new File(videoDir, fileName);
            if (external.isFile() && external.length() > 0) {
                return Optional.of(external.toURI().toString());
            }
        } catch (Exception e) {
            log.debug("Не удалось проверить {} в {}: {}", fileName, videoDir, e.toString());
        }

        // 2. Ресурс внутри jar.
        URL bundled = getClass().getResource("/videos/" + fileName);
        if (bundled != null) {
            return Optional.of(bundled.toExternalForm());
        }

        log.info("Видеоинструкция не найдена: {} (искали в {} и в /videos)", fileName, videoDir);
        return Optional.empty();
    }
}
