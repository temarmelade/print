package com.printkiosk.server.service;

import com.printkiosk.server.config.KioskServerProperties;
import com.printkiosk.server.exception.DocumentConversionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Конвертация DOC/DOCX → PDF через LibreOffice в headless-режиме.
 *
 * <p><b>Профили.</b> LibreOffice при запуске создаёт профиль пользователя
 * (по умолчанию в ~/.config). В контейнере приложение работает от
 * системного пользователя без домашней папки, и soffice падает с
 * «User installation could not be completed» (код 77). Поэтому профиль
 * задаём явно ключом {@code -env:UserInstallation}.
 *
 * <p><b>Параллельность.</b> С общим профилем одновременные запуски теряют
 * результат: второй soffice передаёт работу первому и выходит с кодом 0,
 * а PDF не появляется (проверено: 4 одновременных запуска → 2 PDF).
 * Поэтому держим пул «слотов», у каждого свой профиль; одновременно
 * работает не больше {@code kiosk.conversion.max-parallel} процессов,
 * остальные ждут своей очереди в пределах таймаута. Профили живут между
 * конвертациями — повторный запуск не тратит время на их создание.
 *
 * <p><b>Таймаут.</b> /usr/bin/soffice — скрипт, который запускает
 * soffice.bin дочерним процессом. Убить только родителя мало: soffice.bin
 * останется висеть и держать профиль. Убиваем всё дерево процессов.
 */
@Slf4j
@Service
public class DocumentConversionService {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Где искать soffice, если путь не задан в настройках. */
    private static final List<String> KNOWN_PATHS = List.of(
            "/usr/bin/soffice",
            "/usr/lib/libreoffice/program/soffice",
            "/opt/libreoffice/program/soffice",
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
            "/Applications/LibreOffice.app/Contents/MacOS/soffice");

    /** Сколько символов вывода soffice класть в лог при ошибке. */
    private static final int MAX_LOG_CHARS = 2_000;

    private final KioskServerProperties.Conversion config;
    private final Path workRoot;
    private final BlockingQueue<Integer> freeSlots;
    private final int slotCount;

    public DocumentConversionService(KioskServerProperties properties) {
        this.config = properties.getConversion();
        String dir = config.getWorkDir();
        this.workRoot = (dir == null || dir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "kiosk-lo")
                : Path.of(dir);

        this.slotCount = Math.max(1, config.getMaxParallel());
        this.freeSlots = new ArrayBlockingQueue<>(slotCount);
        for (int i = 0; i < slotCount; i++) freeSlots.add(i);
    }

    /** Сразу после старта говорим в лог, будет ли работать приём Word-файлов. */
    @EventListener(ApplicationReadyEvent.class)
    public void logAvailability() {
        String soffice = findSoffice();
        if (soffice != null) {
            log.info("Конвертация DOC/DOCX → PDF: LibreOffice {}, профили в {}, параллельно до {}",
                    soffice, workRoot, slotCount);
        } else {
            log.warn("Конвертация DOC/DOCX → PDF НЕДОСТУПНА: LibreOffice (soffice) не найден. "
                    + "Word-файлы будут отклоняться. Установите LibreOffice или задайте "
                    + "kiosk.conversion.soffice-path.");
        }
    }

    public boolean isConvertibleToPdf(String mimeType) {
        return DOCX_MIME.equalsIgnoreCase(mimeType);
    }

    /**
     * Конвертирует файл в PDF. PDF появляется рядом с исходником под тем же
     * именем с расширением .pdf — удалять его должен вызывающий.
     *
     * @throws DocumentConversionException LibreOffice не найден, не уложился
     *         в таймаут или не смог открыть документ
     */
    public ConvertedDocument convertToPdf(String inputFilePath) {
        Path input = Path.of(inputFilePath).toAbsolutePath();
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Файл для конвертации не найден: " + input);
        }

        String soffice = findSoffice();
        if (soffice == null) {
            throw new DocumentConversionException(
                    "LibreOffice (soffice) не найден — конвертация DOC/DOCX недоступна");
        }

        Duration timeout = config.getTimeout();
        Integer slot = takeSlot(timeout);
        long started = System.nanoTime();
        try {
            Path pdf = runSoffice(soffice, slot, input, timeout);
            long size = Files.size(pdf);
            log.info("Word → PDF за {} мс: {} ({} Б)",
                    (System.nanoTime() - started) / 1_000_000, pdf.getFileName(), size);
            return new ConvertedDocument(pdf.toString(), "application/pdf", size);
        } catch (IOException e) {
            throw new DocumentConversionException("Ошибка ввода-вывода при конвертации: " + e.getMessage(), e);
        } finally {
            freeSlots.add(slot);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутреннее
    // ════════════════════════════════════════════════════════════════

    private Integer takeSlot(Duration timeout) {
        try {
            Integer slot = freeSlots.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (slot == null) {
                throw new DocumentConversionException(
                        "Конвертер занят: все слоты (" + slotCount + ") заняты дольше "
                                + timeout.toSeconds() + " с");
            }
            return slot;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentConversionException("Ожидание конвертера прервано", e);
        }
    }

    private Path runSoffice(String soffice, int slot, Path input, Duration timeout) throws IOException {
        Files.createDirectories(workRoot);
        Path profile = workRoot.resolve("profile-" + slot);
        Path outDir  = input.getParent();
        Path logFile = Files.createTempFile(workRoot, "soffice-", ".log");

        List<String> cmd = new ArrayList<>(List.of(
                soffice,
                "--headless",
                "--norestore",
                "--nologo",
                "--nodefault",
                "--nolockcheck",
                "-env:UserInstallation=" + profile.toUri(),
                "--convert-to", "pdf",
                "--outdir", outDir.toString(),
                input.toString()));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                // В файл, а не в pipe: непрочитанный pipe переполняется, и
                // soffice встаёт на записи в stdout до самого таймаута.
                .redirectOutput(logFile.toFile());
        // Некоторые части LibreOffice всё же заглядывают в HOME — пусть там
        // будет существующая папка, доступная на запись.
        pb.environment().put("HOME", workRoot.toString());

        try {
            Process process = pb.start();
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                killTree(process);
                Thread.currentThread().interrupt();
                throw new DocumentConversionException("Конвертация прервана", e);
            }

            if (!finished) {
                killTree(process);
                // Убитый soffice оставляет файл блокировки профиля — снимаем,
                // чтобы следующий запуск в этом слоте не споткнулся о него.
                Files.deleteIfExists(profile.resolve(".lock"));
                throw new DocumentConversionException(
                        "LibreOffice не уложился в " + timeout.toMillis() + " мс");
            }

            Path pdf = outDir.resolve(baseName(input) + ".pdf");
            if (process.exitValue() != 0 || !isPdf(pdf)) {
                String output = readLog(logFile);
                Files.deleteIfExists(pdf);
                throw new DocumentConversionException(
                        "LibreOffice не создал PDF (код " + process.exitValue() + "): " + output);
            }
            return pdf;
        } finally {
            Files.deleteIfExists(logFile);
        }
    }

    /** Родитель (скрипт soffice) и все потомки (oosplash, soffice.bin). */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static boolean isPdf(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < 5) return false;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(5);
            return new String(head, StandardCharsets.US_ASCII).equals("%PDF-");
        }
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String readLog(Path logFile) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8).trim();
            return text.length() > MAX_LOG_CHARS ? text.substring(0, MAX_LOG_CHARS) + "…" : text;
        } catch (IOException e) {
            return "(вывод soffice недоступен: " + e.getMessage() + ")";
        }
    }

    /** Настроенный путь, затем стандартные места, затем PATH. null — не найден. */
    private String findSoffice() {
        String configured = config.getSofficePath();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (Files.isExecutable(p)) return p.toString();
            log.warn("kiosk.conversion.soffice-path={} не найден или не исполняемый", configured);
        }
        for (String candidate : KNOWN_PATHS) {
            if (Files.isExecutable(Path.of(candidate))) return candidate;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                for (String exe : List.of("soffice", "soffice.exe", "libreoffice")) {
                    try {
                        Path p = Path.of(dir, exe);
                        if (Files.isExecutable(p)) return p.toString();
                    } catch (RuntimeException ignored) {
                        // кривая запись в PATH (кавычки и т.п.) — пропускаем
                    }
                }
            }
        }
        return null;
    }

    public record ConvertedDocument(
            String filePath,
            String mimeType,
            long fileSizeBytes
    ) {
    }
}
