package com.printkiosk.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Приводит рекламу к виду, который киоск покажет в исходных пропорциях.
 *
 * <p>Браузер (и админка) выполняет метаданные поворота, а JavaFX на
 * киоске — нет. Телефон же почти всегда хранит вертикальный кадр как
 * горизонтальный с пометкой «повернуть на 90°». В итоге в админке ролик
 * вертикальный, а на киоске — боком, в другом формате. Поэтому один раз,
 * при загрузке, делаем так, чтобы пиксели файла уже стояли правильно:
 *
 * <ul>
 *   <li><b>JPEG с EXIF-ориентацией</b> — поворачиваем/отражаем пиксели,
 *       метку убираем (пересохранение без EXIF).</li>
 *   <li><b>Видео</b> перекодируем в H.264/MP4, если JavaFX покажет его не
 *       так или не покажет вовсе: тег поворота, неквадратные пиксели (SAR),
 *       кодек не H.264 (HEVC с iPhone, VP8/VP9 в WebM), не yuv420p
 *       (10-битное видео), контейнер не MP4.</li>
 * </ul>
 *
 * <p>Файлы без таких особенностей не трогаем: без потери качества и без
 * задержки загрузки. Любая ошибка (нет ffmpeg, битый файл, таймаут) —
 * оставляем исходник как есть, загрузку не роняем.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdMediaNormalizer {

    /** Перекодирование минутного ролика на слабом VPS укладывается с запасом. */
    private static final int VIDEO_TIMEOUT_SEC = 300;
    private static final float JPEG_QUALITY = 0.92f;

    private final ObjectMapper json;

    @Value("${kiosk.ads.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${kiosk.ads.ffprobe-path:ffprobe}")
    private String ffprobePath;

    // ════════════════════════════════════════════════════════════════
    //  Фото
    // ════════════════════════════════════════════════════════════════

    /**
     * Поворачивает пиксели JPEG по EXIF-ориентации.
     *
     * @return true — файл переписан на месте
     */
    public boolean normalizeJpeg(Path file) {
        try {
            int orientation = readJpegOrientation(file);
            if (orientation <= 1) return false;

            BufferedImage src = ImageIO.read(file.toFile());
            if (src == null) {
                // Например, CMYK-JPEG: ImageIO его не читает. Оставляем как есть.
                log.warn("EXIF-ориентация {} у {}, но ImageIO не прочитал файл — оставляем как есть",
                        orientation, file.getFileName());
                return false;
            }
            BufferedImage upright = applyOrientation(src, orientation);

            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            writeJpeg(upright, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            log.info("Фото повёрнуто по EXIF (ориентация {}): {}×{} → {}×{}",
                    orientation, src.getWidth(), src.getHeight(),
                    upright.getWidth(), upright.getHeight());
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Не удалось выпрямить фото {}: {}", file.getFileName(), e.getMessage());
            return false;
        }
    }

    /**
     * EXIF-ориентация JPEG (тег 0x0112): 1 — как есть, 2…8 — нужен поворот
     * или отражение. Не JPEG, нет EXIF или не разобрать — 1.
     */
    static int readJpegOrientation(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readUnsignedShort() != 0xFFD8) return 1;          // не JPEG
            while (true) {
                int marker = in.readUnsignedShort();
                if ((marker & 0xFF00) != 0xFF00) return 1;
                if (marker == 0xFFDA || marker == 0xFFD9) return 1;   // дальше — данные изображения
                int length = in.readUnsignedShort();
                if (length < 2) return 1;
                byte[] segment = in.readNBytes(length - 2);
                if (segment.length < length - 2) return 1;
                if (marker == 0xFFE1 && isExif(segment)) {
                    return orientationFromTiff(segment, 6);
                }
            }
        } catch (EOFException e) {
            return 1;
        }
    }

    private static boolean isExif(byte[] s) {
        return s.length > 14 && s[0] == 'E' && s[1] == 'x' && s[2] == 'i' && s[3] == 'f'
                && s[4] == 0 && s[5] == 0;
    }

    /** Разбор IFD0 внутри TIFF-заголовка EXIF, поиск тега Orientation. */
    private static int orientationFromTiff(byte[] b, int base) {
        boolean le;
        if (b[base] == 'I' && b[base + 1] == 'I') le = true;
        else if (b[base] == 'M' && b[base + 1] == 'M') le = false;
        else return 1;

        long ifd = u32(b, base + 4, le);
        long p = base + ifd;
        if (ifd < 8 || p + 2 > b.length) return 1;
        int entries = u16(b, (int) p, le);
        for (int i = 0; i < entries; i++) {
            int e = (int) p + 2 + i * 12;
            if (e + 12 > b.length) return 1;
            if (u16(b, e, le) == 0x0112) {
                int v = u16(b, e + 8, le);
                return (v >= 1 && v <= 8) ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int i, boolean le) {
        return le ? (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8
                  : (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    private static long u32(byte[] b, int i, boolean le) {
        return le ? (u16(b, i, true) & 0xFFFFL) | (long) u16(b, i + 2, true) << 16
                  : (long) u16(b, i, false) << 16 | (u16(b, i + 2, false) & 0xFFFFL);
    }

    /**
     * Переставляет пиксели так, как их показал бы просмотрщик, учитывающий
     * EXIF. Для 5–8 ширина и высота меняются местами.
     */
    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = orientation >= 5;
        int ow = swap ? h : w, oh = swap ? w : h;

        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[ow * oh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ox, oy;
                switch (orientation) {
                    case 2  -> { ox = w - 1 - x; oy = y; }             // отражение по горизонтали
                    case 3  -> { ox = w - 1 - x; oy = h - 1 - y; }     // поворот на 180°
                    case 4  -> { ox = x;         oy = h - 1 - y; }     // отражение по вертикали
                    case 5  -> { ox = y;         oy = x; }             // транспонирование
                    case 6  -> { ox = h - 1 - y; oy = x; }             // поворот на 90° по часовой
                    case 7  -> { ox = h - 1 - y; oy = w - 1 - x; }     // антитранспонирование
                    case 8  -> { ox = y;         oy = w - 1 - x; }     // поворот на 90° против часовой
                    default -> { ox = x;         oy = y; }
                }
                out[oy * ow + ox] = in[y * w + x];
            }
        }
        BufferedImage result = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, ow, oh, out, 0, ow);
        return result;
    }

    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Видео
    // ════════════════════════════════════════════════════════════════

    /**
     * Перекодирует видео в H.264/MP4, если JavaFX покажет его не в исходных
     * пропорциях или не покажет вовсе.
     *
     * @return путь к новому MP4 (рядом с исходником) или пусто — исходник
     *         подходит как есть, либо перекодировать не удалось
     */
    public Optional<Path> normalizeVideo(Path file) {
        VideoInfo info = probe(file);
        if (info == null) return Optional.empty();

        List<String> reasons = new ArrayList<>();
        if (!"h264".equals(info.codec())) reasons.add("кодек " + info.codec());
        if (!"yuv420p".equals(info.pixFmt())) reasons.add("формат пикселей " + info.pixFmt());
        if (info.rotation() % 360 != 0) reasons.add("поворот " + info.rotation() + "°");
        if (!info.squarePixels()) reasons.add("неквадратные пиксели " + info.sar());
        if (!file.getFileName().toString().toLowerCase().endsWith(".mp4")) reasons.add("контейнер не MP4");
        if (reasons.isEmpty()) return Optional.empty();

        Path output = file.resolveSibling(baseName(file) + ".norm.mp4");
        log.info("Видео {} ({}×{}) перекодируем для киоска: {}",
                file.getFileName(), info.width(), info.height(), String.join(", ", reasons));

        List<String> cmd = List.of(
                ffmpegPath, "-y", "-v", "error",
                "-i", file.toString(),
                "-map", "0:v:0", "-map", "0:a:0?",
                // Поворот ffmpeg применяет сам (autorotate включён по умолчанию)
                // и убирает тег. Здесь — квадратные пиксели и чётные размеры.
                "-vf", "scale=trunc(iw*sar/2)*2:trunc(ih/2)*2,setsar=1",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                output.toString());

        if (!run(cmd, VIDEO_TIMEOUT_SEC) || !Files.isRegularFile(output)) {
            deleteQuietly(output);
            log.warn("Видео {} перекодировать не удалось — оставляем исходник", file.getFileName());
            return Optional.empty();
        }
        VideoInfo after = probe(output);
        if (after != null) {
            log.info("Видео готово для киоска: {}×{} → {}×{}",
                    info.width(), info.height(), after.width(), after.height());
        }
        return Optional.of(output);
    }

    /** Сведения о первом видеопотоке. null — ffprobe недоступен или не разобрал файл. */
    VideoInfo probe(Path file) {
        Path out = null;
        try {
            out = Files.createTempFile("ffprobe-", ".json");
            Process p = new ProcessBuilder(
                    ffprobePath, "-v", "error",
                    "-select_streams", "v:0", "-show_streams",
                    "-of", "json", file.toString())
                    .redirectErrorStream(false)
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                killTree(p);
                return null;
            }
            if (p.exitValue() != 0) return null;

            JsonNode stream = json.readTree(out.toFile()).path("streams").path(0);
            if (stream.isMissingNode()) return null;

            int rotation = stream.path("tags").path("rotate").asInt(0);
            for (JsonNode sd : stream.path("side_data_list")) {
                if (sd.has("rotation")) rotation = sd.path("rotation").asInt(rotation);
            }
            String sar = stream.path("sample_aspect_ratio").asText("1:1");
            return new VideoInfo(
                    stream.path("codec_name").asText(""),
                    stream.path("pix_fmt").asText(""),
                    stream.path("width").asInt(0),
                    stream.path("height").asInt(0),
                    Math.abs(rotation),
                    sar,
                    isSquare(sar));
        } catch (IOException e) {
            log.info("ffprobe недоступен ({}) — видео рекламы сохраняем как есть", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            deleteQuietly(out);
        }
    }

    /** «1:1», «0:1» (не задано), «N/A» — квадратные; «4:3» и т.п. — нет. */
    private static boolean isSquare(String sar) {
        String[] parts = sar.split(":");
        if (parts.length != 2) return true;
        try {
            long num = Long.parseLong(parts[0]), den = Long.parseLong(parts[1]);
            return num == 0 || den == 0 || num == den;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    record VideoInfo(String codec, String pixFmt, int width, int height,
                     int rotation, String sar, boolean squarePixels) {}

    // ════════════════════════════════════════════════════════════════
    //  Запуск процессов
    // ════════════════════════════════════════════════════════════════

    private boolean run(List<String> cmd, int timeoutSec) {
        Path logFile = null;
        try {
            logFile = Files.createTempFile("ffmpeg-", ".log");
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())   // в файл: полный pipe повесил бы процесс
                    .start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                killTree(p);
                log.warn("ffmpeg не уложился в {} с", timeoutSec);
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("ffmpeg завершился с кодом {}: {}", p.exitValue(), tail(logFile));
                return false;
            }
            return true;
        } catch (IOException e) {
            log.info("ffmpeg недоступен ({})", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            deleteQuietly(logFile);
        }
    }

    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static String tail(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return s.length() > 800 ? "…" + s.substring(s.length() - 800) : s;
        } catch (IOException e) {
            return "";
        }
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // временный файл — не критично
        }
    }
}
