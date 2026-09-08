package com.printkiosk.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Перегоняет GIF в MP4 (H.264) при загрузке рекламы.
 *
 * <h2>Зачем</h2>
 * GIF — плохой формат для полноэкранного показа: кадры почти не сжаты,
 * палитра 256 цветов, а декодер JavaFX разбирает каждый кадр на потоке
 * отрисовки без аппаратного ускорения. На мини-ПК это видно как рывки.
 * H.264 декодируется видеокартой, файл меньше в разы, и тот же ролик
 * играет плавно.
 *
 * <p>Конвертация одноразовая — при загрузке в админке, а не на киоске.
 *
 * <h2>Если ffmpeg нет</h2>
 * Возвращаем пусто, и загрузка идёт как раньше: GIF сохраняется как есть.
 * Отсутствие ffmpeg на машине разработчика не должно ломать админку.
 */
@Slf4j
@Component
public class GifToVideoConverter {

    /** Ролик длиной больше пары минут — почти наверняка ошибка загрузки. */
    private static final int TIMEOUT_SEC = 120;

    @Value("${kiosk.ads.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    /**
     * @return путь к готовому MP4 или пусто, если конвертация невозможна
     */
    public Optional<Path> convert(Path gif) {
        Path output = gif.resolveSibling(
                gif.getFileName().toString().replaceAll("\\.gif$", "") + ".mp4");

        try {
            Process process = new ProcessBuilder(
                    ffmpegPath, "-y",
                    "-i", gif.toString(),
                    // yuv420p обязателен: без него файл не откроется ни в
                    // JavaFX, ни в большинстве плееров.
                    "-pix_fmt", "yuv420p",
                    // Ширина и высота H.264 должны быть чётными, иначе
                    // кодирование падает. Формула округляет вниз.
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    "-c:v", "libx264",
                    "-preset", "slow",     // ролик кодируется один раз, спешить некуда
                    "-crf", "23",
                    "-movflags", "+faststart",
                    output.toString())
                    .redirectErrorStream(true)
                    .start();

            // Вывод обязательно читаем: заполнится буфер трубы — процесс
            // повиснет навсегда, и таймаут ниже уже не поможет.
            String ffmpegOutput = new String(process.getInputStream().readAllBytes());

            if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffmpeg не уложился в {} с — оставляем GIF", TIMEOUT_SEC);
                Files.deleteIfExists(output);
                return Optional.empty();
            }

            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                log.warn("ffmpeg завершился с ошибкой, оставляем GIF:\n{}",
                        tail(ffmpegOutput));
                Files.deleteIfExists(output);
                return Optional.empty();
            }

            log.info("GIF сконвертирован в MP4: {} КБ → {} КБ",
                    Files.size(gif) / 1024, Files.size(output) / 1024);
            return Optional.of(output);

        } catch (IOException e) {
            log.info("ffmpeg недоступен ({}) — реклама останется в GIF", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Последние строки вывода — полный лог ffmpeg слишком длинный. */
    private static String tail(String text) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 8);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
