package com.printkiosk.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DocumentConversionService {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public boolean isConvertibleToPdf(String mimeType) {
        return DOCX_MIME.equalsIgnoreCase(mimeType);
    }

    public ConvertedDocument convertToPdf(String inputFilePath) throws Exception {
        Path inputPath = Path.of(inputFilePath);

        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("Файл для конвертации не найден: " + inputFilePath);
        }

        Path outputDir = inputPath.getParent();
        String sofficePath = findLibreOfficePath();

        ProcessBuilder processBuilder = new ProcessBuilder(
                sofficePath,
                "--headless",
                "--convert-to",
                "pdf",
                "--outdir",
                outputDir.toAbsolutePath().toString(),
                inputPath.toAbsolutePath().toString()
        );

        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("LibreOffice не успел конвертировать файл за 60 секунд");
        }

        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new RuntimeException("Ошибка LibreOffice при конвертации DOCX в PDF: " + output);
        }

        String originalName = inputPath.getFileName().toString();
        String pdfName = originalName.replaceAll("\\.[^.]+$", ".pdf");
        Path pdfPath = outputDir.resolve(pdfName);

        if (!Files.exists(pdfPath)) {
            throw new RuntimeException("PDF после конвертации не найден: " + pdfPath);
        }

        log.info("DOCX converted to PDF: {}", pdfPath);

        return new ConvertedDocument(
                pdfPath.toAbsolutePath().toString(),
                "application/pdf",
                Files.size(pdfPath)
        );
    }

    private String findLibreOfficePath() {
        String windowsPath = "C:\\Program Files\\LibreOffice\\program\\soffice.exe";

        if (Files.exists(Path.of(windowsPath))) {
            return windowsPath;
        }

        String windowsPathX86 = "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe";

        if (Files.exists(Path.of(windowsPathX86))) {
            return windowsPathX86;
        }

        String macPath = "/Applications/LibreOffice.app/Contents/MacOS/soffice";

        if (Files.exists(Path.of(macPath))) {
            return macPath;
        }

        return "soffice";
    }

    public record ConvertedDocument(
            String filePath,
            String mimeType,
            long fileSizeBytes
    ) {
    }
}