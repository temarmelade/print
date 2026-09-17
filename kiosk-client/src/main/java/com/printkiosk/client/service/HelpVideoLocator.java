package com.printkiosk.client.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Ищет видеоинструкцию для раздела помощи.
 *
 * <h2>Почему поиск не по точному имени</h2>
 * Сначала было простое {@code new File(dir, "help_print.mp4")}, и ролик
 * регулярно «не находился» при живом файле в папке. Причины бытовые:
 *
 * <ul>
 *   <li>Windows по умолчанию скрывает известные расширения. Человек
 *       переименовывает файл в {@code help_print.mp4}, а на диске
 *       получается {@code help_print.mp4.mp4};</li>
 *   <li>камера отдаёт {@code .MP4} в верхнем регистре;</li>
 *   <li>ролик оказывается {@code .mov} или {@code .webm}.</li>
 * </ul>
 *
 * <p>Поэтому ищем любой файл, чьё имя начинается с {@code help_<раздел>}
 * и имеет видеорасширение, без учёта регистра.
 */
@Slf4j
@Component
public class HelpVideoLocator {

    /** Расширения, которые вообще имеет смысл пробовать. */
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".m4v", ".mov", ".webm");

    @Value("${kiosk.help.video-dir:C:/PrintKiosk/videos}")
    private String videoDir;

    /**
     * @param topic {@code print} | {@code copy} | {@code scan}
     * @return URL для MediaPlayer или пусто, если ролика нет
     */
    public Optional<String> find(String topic) {
        String prefix = ("help_" + topic).toLowerCase();

        // 1. Папка на диске киоска — её содержимое можно менять без пересборки.
        try {
            File dir = new File(videoDir);
            File[] files = dir.listFiles();

            if (files != null) {
                Optional<File> match = Arrays.stream(files)
                        .filter(File::isFile)
                        .filter(f -> f.length() > 0)
                        .filter(f -> matches(f.getName(), prefix))
                        .findFirst();

                if (match.isPresent()) {
                    log.info("Видеоинструкция '{}': {}", topic, match.get().getName());
                    return Optional.of(match.get().toURI().toString());
                }

                // Показываем, что реально лежит в папке — по логу сразу видно,
                // что файл назван не так, как ожидается.
                log.info("Видео для '{}' в {} не найдено. Файлы в папке: {}",
                        topic, videoDir,
                        files.length == 0 ? "<пусто>"
                                : Arrays.stream(files).map(File::getName).toList());
            } else {
                log.info("Папка с видео не найдена: {}", videoDir);
            }
        } catch (Exception e) {
            log.debug("Не удалось прочитать {}: {}", videoDir, e.toString());
        }

        // 2. Ресурс внутри jar — вариант «из коробки».
        for (String ext : VIDEO_EXTENSIONS) {
            URL bundled = getClass().getResource("/videos/" + prefix + ext);
            if (bundled != null) {
                log.info("Видеоинструкция '{}' взята из jar", topic);
                return Optional.of(bundled.toExternalForm());
            }
        }

        log.info("Видеоинструкция '{}' отсутствует", topic);
        return Optional.empty();
    }

    /**
     * Имя подходит, если начинается с нужного префикса и заканчивается
     * видеорасширением. Двойное расширение ({@code help_print.mp4.mp4})
     * тоже проходит — именно его создаёт Windows со скрытыми расширениями.
     */
    private static boolean matches(String fileName, String prefix) {
        String lower = fileName.toLowerCase();
        if (!lower.startsWith(prefix)) return false;
        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
