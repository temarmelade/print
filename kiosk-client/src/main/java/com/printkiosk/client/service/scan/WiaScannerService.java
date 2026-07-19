package com.printkiosk.client.service.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * Реальное сканирование на Windows через WIA.
 *
 * <p><b>Почему PowerShell, а не Java-библиотека.</b> В стандартном Java нет API
 * доступа к сканерам (в отличие от печати с javax.print). Сканеры на Windows
 * работают через WIA/TWAIN — нативные COM-интерфейсы. Самый надёжный мост без
 * сторонних зависимостей — вызвать WIA из PowerShell и забрать готовый файл.
 *
 * <p>Активируется при {@code kiosk.scanner.mock=false}. По умолчанию (и в dev)
 * работает мок — эта реализация не мешает разработке без железа.
 *
 * <p><b>Однопоточность.</b> Сканер физически один: два одновременных Transfer
 * приведут к ошибке «устройство занято». Поэтому все задания идут через
 * single-thread executor — строго по очереди.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kiosk.scanner.mock", havingValue = "false")
public class WiaScannerService implements ScannerService {

    /** JPEG WIA format GUID. */
    private static final String WIA_FORMAT_JPEG = "{B96B3CAE-0728-11D3-9D7B-0000F81EF32E}";

    /** Скан A4 может идти долго — но не бесконечно. */
    private static final long SCAN_TIMEOUT_SEC = 90;

    private final ExecutorService scanExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wia-scanner");
                t.setDaemon(true);
                return t;
            });

    /** Точное имя WIA-устройства. Пусто → берём первый доступный сканер. */
    @Value("${kiosk.scanner.device-name:}")
    private String deviceName;

    /** DPI сканирования. 200 — разумный баланс качество/размер для копира. */
    @Value("${kiosk.scanner.dpi:200}")
    private int dpi;

    @Override
    public CompletableFuture<File> scanPage() {
        CompletableFuture<File> future = new CompletableFuture<>();
        scanExecutor.submit(() -> {
            try {
                future.complete(runWiaScan());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isReady() {
        return CompletableFuture.supplyAsync(this::deviceAvailable, scanExecutor);
    }

    // ════════════════════════════════════════════════════════════════
    //  WIA через PowerShell
    // ════════════════════════════════════════════════════════════════

    private File runWiaScan() throws IOException, InterruptedException, TimeoutException {
        Path out = Files.createTempFile("scan_", ".jpg");
        Files.deleteIfExists(out);   // WIA откажется писать в существующий файл

        String script = buildScanScript(out);
        int exit = runPowerShell(script, SCAN_TIMEOUT_SEC);

        if (exit != 0) {
            throw new IOException("Сканирование не удалось (код " + exit + "). "
                    + "Проверьте, что крышка закрыта и сканер не занят.");
        }
        if (!Files.exists(out) || Files.size(out) == 0) {
            throw new IOException("Сканер не вернул изображение");
        }

        log.info("WIA scan complete → {} ({} байт)", out, Files.size(out));
        return out.toFile();
    }

    private boolean deviceAvailable() {
        try {
            String script = """
                    $ErrorActionPreference = 'Stop'
                    $dm = New-Object -ComObject WIA.DeviceManager
                    if ($dm.DeviceInfos.Count -gt 0) { exit 0 } else { exit 1 }
                    """;
            return runPowerShell(script, 15) == 0;
        } catch (Exception e) {
            log.warn("Проверка сканера не удалась: {}", e.getMessage());
            return false;
        }
    }

    /**
     * PowerShell-скрипт: находит устройство (по имени или первое), выставляет
     * DPI и снимает страницу в JPEG. Экранирование пути — через одинарные
     * кавычки PowerShell с удвоением внутренних апострофов.
     */
    private String buildScanScript(Path out) {
        String outPath = psQuote(out.toAbsolutePath().toString());
        String nameFilter = (deviceName == null || deviceName.isBlank())
                ? "$dm.DeviceInfos.Item(1)"
                : "$dm.DeviceInfos | Where-Object { $_.Properties('Name').Value -eq "
                    + psQuote(deviceName) + " } | Select-Object -First 1";

        return """
               $ErrorActionPreference = 'Stop'
               $dm = New-Object -ComObject WIA.DeviceManager
               $info = %NAME%
               if (-not $info) { Write-Error 'Сканер не найден'; exit 2 }
               $device = $info.Connect()
               $item = $device.Items.Item(1)

               # DPI по горизонтали и вертикали
               function SetProp($props, $id, $val) {
                 foreach ($p in $props) { if ($p.PropertyID -eq $id) { $p.Value = $val } }
               }
               SetProp $item.Properties 6147 %DPI%   # Horizontal Resolution
               SetProp $item.Properties 6148 %DPI%   # Vertical Resolution

               $image = $item.Transfer('%FORMAT%')
               $image.SaveFile(%OUT%)
               exit 0
               """
                .replace("%NAME%", nameFilter)
                .replace("%DPI%", String.valueOf(dpi))
                .replace("%FORMAT%", WIA_FORMAT_JPEG)
                .replace("%OUT%", outPath);
    }

    private int runPowerShell(String script, long timeoutSec)
            throws IOException, InterruptedException, TimeoutException {

        // Скрипт передаём в кодировке Base64 (-EncodedCommand): так не ломается
        // экранирование кавычек и кириллицы в аргументах командной строки.
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

        Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var in = process.getInputStream()) {
                output.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        });
        reader.setDaemon(true);
        reader.start();

        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("Сканер не ответил за " + timeoutSec + " с");
        }
        reader.join(2000);

        int exit = process.exitValue();
        if (exit != 0) {
            log.warn("PowerShell (scan) exit={} output={}", exit, output.toString().trim());
        }
        return exit;
    }

    /** Строка в одинарных кавычках PowerShell (внутренние ' удваиваются). */
    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
