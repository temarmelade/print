package com.printkiosk.client.startup;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PrintSpoolerCleaner {

    /** Таймаут на одну shell-команду. На реальном железе net stop/start укладывается в 5–10 сек. */
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    /** Net stop возвращает control до полной остановки службы — даём ей время добить файлы. */
    private static final long SPOOLER_SHUTDOWN_WAIT_MS = 1500;

    private static final Charset WINDOWS_CONSOLE_CHARSET = Charset.forName("Cp866");

    @Value("${kiosk.printer.cleanup-on-startup:true}")
    private boolean enabled;

    @PostConstruct
    public void cleanupPrintSpooler() {
        if (!enabled) {
            log.info("Print spooler cleanup disabled by config " +
                    "(kiosk.printer.cleanup-on-startup=false)");
            return;
        }

        if (!isWindows()) {
            log.info("Print spooler cleanup skipped: not running on Windows");
            return;
        }

        log.info("Print spooler cleanup: starting");

        int stopResult = executeStep(
                "stop Spooler service",
                new String[]{"cmd.exe", "/c", "net", "stop", "spooler"}
        );

        boolean spoolerHalted = (stopResult == 0 || stopResult == 2);
        if (!spoolerHalted) {
            log.warn("Failed to stop Print Spooler (exit code {}). " +
                            "Most likely the application is NOT running as Administrator. " +
                            "Spool cleanup is skipped — prior pending jobs may still print.",
                    stopResult);
            return;
        }

        try {
            Thread.sleep(SPOOLER_SHUTDOWN_WAIT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Print spooler cleanup interrupted during shutdown wait");
        }

        // ----- Шаг 3: удалить файлы из spool/PRINTERS -----
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            systemRoot = "C:\\Windows"; // fallback
        }
        String spoolPath = systemRoot + "\\System32\\spool\\PRINTERS\\*.*";

        int deleteResult = executeStep(
                "delete spool files (" + spoolPath + ")",
                new String[]{"cmd.exe", "/c", "del", "/Q", "/F", "/S", spoolPath}
        );
        int startResult = executeStep(
                "start Spooler service",
                new String[]{"cmd.exe", "/c", "net", "start", "spooler"}
        );

        boolean spoolerUp = (startResult == 0 || startResult == 2); // 2 = "already started"
        if (!spoolerUp) {
            log.error("FAILED to restart Print Spooler (exit code {}). " +
                            "Printing on this kiosk will NOT work until Spooler is started manually. " +
                            "Run as Administrator: 'net start spooler'.",
                    startResult);
        } else if (deleteResult != 0) {
            log.warn("Print spooler cleanup finished, but spool files deletion " +
                    "returned non-zero (exit code {}). Spooler is back up.", deleteResult);
        } else {
            log.info("Print spooler cleanup: completed successfully");
        }
    }

    private int executeStep(String description, String[] command) {
        log.debug("Print spooler cleanup → {}", description);

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);

            StringBuilder output = new StringBuilder();
            Thread stdoutDrainer = drainStreamAsync(process.getInputStream(), output);
            Thread stderrDrainer = drainStreamAsync(process.getErrorStream(), output);

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Print spooler cleanup: '{}' timed out after {}s, process killed",
                        description, PROCESS_TIMEOUT_SECONDS);
                return -1;
            }

            // дать дренажным потокам дочитать остаток
            stdoutDrainer.join(1000);
            stderrDrainer.join(1000);

            int exitCode = process.exitValue();
            String trimmedOutput = output.toString().trim();

            if (exitCode == 0) {
                log.debug("Print spooler cleanup: '{}' OK. Output: {}",
                        description, trimmedOutput.isEmpty() ? "(empty)" : trimmedOutput);
            } else {
                log.warn("Print spooler cleanup: '{}' exit code {}. Output: {}",
                        description, exitCode, trimmedOutput.isEmpty() ? "(empty)" : trimmedOutput);
            }
            return exitCode;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            log.warn("Print spooler cleanup: '{}' interrupted", description);
            return -1;
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            log.error("Print spooler cleanup: '{}' threw exception", description, e);
            return -1;
        }
    }

    private Thread drainStreamAsync(InputStream stream, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, WINDOWS_CONSOLE_CHARSET))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // поток закрыт вместе с процессом — это норма
            }
        }, "spooler-cleanup-drainer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
