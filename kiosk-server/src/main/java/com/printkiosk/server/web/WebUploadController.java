//package com.printkiosk.server.web;
//
//import com.printkiosk.server.service.PinGeneratorService;
//import com.printkiosk.shared.api.UploadSource;
//import com.printkiosk.server.service.FileValidationService;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.pdfbox.pdmodel.PDDocument;
//import org.apache.poi.xwpf.usermodel.XWPFDocument;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.InputStream;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.nio.file.StandardCopyOption;
//import java.util.UUID;
//
//@Slf4j
//@RestController
//public class WebUploadController {
//
//
//    private final FileValidationService fileValidationService;
//    private final PinGeneratorService pinGeneratorService;
//    public WebUploadController(
//            FileValidationService fileValidationService,
//            PinGeneratorService pinGeneratorService
//    ) {
//        this.fileValidationService = fileValidationService;
//        this.pinGeneratorService = pinGeneratorService;
//    }
//
//    @GetMapping("/ping")
//    public String ping() {
//        return "PONG! Сервер работает и отвечает!";
//    }
//
//    @GetMapping(value = "/upload", produces = "text/html; charset=UTF-8")
//    public String showUploadPage(@RequestParam(defaultValue = "ru") String lang) {
//
//        String subtitle = switch (lang) {
//            case "kg" -> "Басып чыгаруу үчүн файлды тандаңыз:";
//            case "en" -> "Select a file to print:";
//            default -> "Выберите файл для печати:";
//        };
//
//        String allowedText = switch (lang) {
//            case "kg" -> "Колдоого алынган форматтар: PDF, JPG, PNG, DOCX";
//            case "en" -> "Supported formats: PDF, JPG, PNG, DOCX";
//            default -> "Поддерживаемые форматы: PDF, JPG, PNG, DOCX";
//        };
//
//        String btnChoose = switch (lang) {
//            case "kg" -> "📂 Тандоо үчүн басыңыз";
//            case "en" -> "📂 Click to choose file";
//            default -> "📂 Нажмите, чтобы выбрать файл";
//        };
//
//        String btnUpload = switch (lang) {
//            case "kg" -> "🚀 Терминалга жүктөө";
//            case "en" -> "🚀 Upload to terminal";
//            default -> "🚀 Загрузить в терминал";
//        };
//
//        String notSelected = switch (lang) {
//            case "kg" -> "Файл тандала элек";
//            case "en" -> "No file selected";
//            default -> "Файл пока не выбран";
//        };
//
//        return """
//            <html>
//            <head>
//                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
//                <style>
//                    body { font-family: -apple-system, sans-serif; text-align: center; background-color: #f0f2f5; padding: 20px; margin: 0; }
//                    .card { background: white; padding: 30px 20px; border-radius: 20px; box-shadow: 0 10px 20px rgba(0,0,0,0.05); margin-top: 10px; }
//                    .btn-submit { display: block; width: 100%%; padding: 16px; background: #007AFF; color: white; border: none; border-radius: 12px; font-size: 18px; font-weight: bold; margin-top: 25px; cursor: pointer; }
//                    input[type="file"] { display: none; }
//                    .custom-file-upload { display: block; width: 100%%; padding: 25px 10px; border: 2px dashed #007AFF; border-radius: 12px; background: #f8f9fa; color: #333; font-size: 16px; cursor: pointer; margin-top: 20px; box-sizing: border-box; }
//                    #file-name { margin-top: 12px; font-size: 14px; color: #666; word-wrap: break-word; }
//                    .allowed { margin-top: 10px; color: #777; font-size: 13px; }
//                    .lang-bar a { text-decoration: none; font-size: 24px; margin: 0 10px; opacity: 0.5; transition: 0.2s; }
//                    .lang-bar a.active { opacity: 1.0; transform: scale(1.2); display: inline-block; }
//                </style>
//            </head>
//            <body>
//                <div class="lang-bar">
//                    <a href="?lang=ru" class="%s">🇷🇺</a>
//                    <a href="?lang=kg" class="%s">🇰🇬</a>
//                    <a href="?lang=en" class="%s">🇬🇧</a>
//                </div>
//
//                <div class="card">
//                    <h2 style="margin-top: 0;">🖨️ Print Kiosk</h2>
//                    <p style="color: #666;">%s</p>
//                    <p class="allowed">%s</p>
//
//                    <form action="/api/upload-file" method="POST" enctype="multipart/form-data">
//                        <input type="hidden" name="lang" value="%s">
//
//                        <label class="custom-file-upload">
//                            <input type="file" name="file" required
//                                   accept="application/pdf,image/jpeg,image/png,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.pdf,.jpg,.jpeg,.png,.docx"
//                                   onchange="document.getElementById('file-name').innerHTML = '✅ <b>' + this.files[0].name + '</b>';">
//                            %s
//                        </label>
//
//                        <div id="file-name">%s</div>
//                        <button type="submit" class="btn-submit">%s</button>
//                    </form>
//                </div>
//            </body>
//            </html>
//            """.formatted(
//                lang.equals("ru") ? "active" : "",
//                lang.equals("kg") ? "active" : "",
//                lang.equals("en") ? "active" : "",
//                subtitle,
//                allowedText,
//                lang,
//                btnChoose,
//                notSelected,
//                btnUpload
//        );
//    }
//
//    @PostMapping(value = "/api/upload-file", produces = "text/html; charset=UTF-8")
//    public String handleFileUpload(
//            @RequestParam("file") MultipartFile file,
//            @RequestParam(defaultValue = "ru") String lang
//    ) {
//        if (file.isEmpty()) {
//            return errorPage(
//                    switch (lang) {
//                        case "kg" -> "Файл бош.";
//                        case "en" -> "File is empty.";
//                        default -> "Файл пуст.";
//                    },
//                    lang
//            );
//        }
//
//        try {
//            String originalFileName = file.getOriginalFilename();
//
//            FileValidationService.ValidationResult validation = fileValidationService.validate(
//                    file
//            );
//
//            if (!validation.valid()) {
//                return errorPage(validation.reason().toString(), lang);
//            }
//
//            String mimeType = validation.normalizedMimeType();
//
//            Path tempDir = Paths.get(
//                    System.getProperty("user.home"),
//                    "Desktop",
//                    "KioskTempFiles"
//            );
//
//            if (!Files.exists(tempDir)) {
//                Files.createDirectories(tempDir);
//            }
//
//            String safeOriginalName = originalFileName;
//
//            if (safeOriginalName == null || safeOriginalName.isBlank()) {
//                safeOriginalName = "web_uploaded_file";
//            }
//
//            String extension = resolveExtension(mimeType, safeOriginalName);
//            Path localFilePath = tempDir.resolve(UUID.randomUUID() + extension);
//
//            Files.copy(file.getInputStream(), localFilePath, StandardCopyOption.REPLACE_EXISTING);
//
//            int pageCount = calculatePageCount(localFilePath, mimeType, extension);
//
//            String pin = pinGeneratorService.pickUnusedPin();
//
//            return successPage(pin, pageCount, lang);
//
//        } catch (Exception e) {
//            log.error("Ошибка веб-загрузки", e);
//
//            return errorPage(
//                    switch (lang) {
//                        case "kg" -> "Файлды иштетүүдө ката кетти. Балким, файл бузулган.";
//                        case "en" -> "File processing error. The file may be damaged.";
//                        default -> "Ошибка обработки файла. Возможно, файл повреждён.";
//                    },
//                    lang
//            );
//        }
//    }
//
//    private int calculatePageCount(Path localFilePath, String mimeType, String extension) throws Exception {
//        if (FileValidationService.MIME_PDF.equals(mimeType)) {
//            try (PDDocument pdfDocument = org.apache.pdfbox.Loader.loadPDF(localFilePath.toFile())) {
//                return Math.max(pdfDocument.getNumberOfPages(), 1);
//            }
//        }
//
//        if (FileValidationService.MIME_DOCX.equals(mimeType) || ".docx".equals(extension)) {
//            try (InputStream inputStream = Files.newInputStream(localFilePath);
//                 XWPFDocument docx = new XWPFDocument(inputStream)) {
//
//                int pages = docx
//                        .getProperties()
//                        .getExtendedProperties()
//                        .getUnderlyingProperties()
//                        .getPages();
//
//                return Math.max(pages, 1);
//            }
//        }
//
//        if (FileValidationService.MIME_JPEG.equals(mimeType) || FileValidationService.MIME_PNG.equals(mimeType)) {
//            return 1;
//        }
//
//        return 1;
//    }
//
//    private String resolveExtension(String mimeType, String fileName) {
//        String lowerName = fileName == null ? "" : fileName.toLowerCase();
//
//        if (FileValidationService.MIME_PDF.equals(mimeType)) {
//            return ".pdf";
//        }
//
//        if (FileValidationService.MIME_DOCX.equals(mimeType)) {
//            return ".docx";
//        }
//
//        if (FileValidationService.MIME_PNG.equals(mimeType)) {
//            return ".png";
//        }
//
//        if (FileValidationService.MIME_JPEG.equals(mimeType)) {
//            if (lowerName.endsWith(".jpeg")) {
//                return ".jpeg";
//            }
//
//            return ".jpg";
//        }
//
//        return ".bin";
//    }
//
//    private String successPage(String pin, int pageCount, String lang) {
//        String successTitle = switch (lang) {
//            case "kg" -> "✅ Ийгиликтүү жүктөлдү!";
//            case "en" -> "✅ Successfully uploaded!";
//            default -> "✅ Файл загружен!";
//        };
//
//        String pagesText = switch (lang) {
//            case "kg" -> "Барактардын саны: <b>%d</b>";
//            case "en" -> "Pages found: <b>%d</b>";
//            default -> "Найдено страниц: <b>%d</b>";
//        };
//
//        String instructionText = switch (lang) {
//            case "kg" -> "Бул кодду терминалдын экранына киргизиңиз.";
//            case "en" -> "Enter this code on the kiosk screen.";
//            default -> "Введите этот код на экране киоска.";
//        };
//
//        String btnBack = switch (lang) {
//            case "kg" -> "Дагы жүктөө";
//            case "en" -> "Upload another";
//            default -> "Загрузить ещё";
//        };
//
//        return """
//            <html>
//            <head>
//                <meta name="viewport" content="width=device-width, initial-scale=1.0">
//            </head>
//            <body style="text-align: center; font-family: -apple-system, sans-serif; background-color: #f0f2f5; padding: 20px; margin: 0;">
//                <div style="background: white; padding: 30px 20px; border-radius: 20px; box-shadow: 0 10px 20px rgba(0,0,0,0.05);">
//                    <h2 style="color: #28a745; margin-top: 0;">%s</h2>
//                    <p style="color: #666; font-size: 16px;">%s</p>
//                    <h1 style="font-size: 54px; letter-spacing: 8px; color: #333; margin: 10px 0;">%s</h1>
//                    <p style="color: #666;">%s</p>
//                    <a href="/upload?lang=%s" style="display: inline-block; margin-top: 20px; padding: 12px 24px; background: #e9ecef; color: #333; text-decoration: none; border-radius: 8px; font-weight: bold;">%s</a>
//                </div>
//            </body>
//            </html>
//            """.formatted(
//                successTitle,
//                String.format(pagesText, pageCount),
//                pin,
//                instructionText,
//                lang,
//                btnBack
//        );
//    }
//
//    private String errorPage(String message, String lang) {
//        String title = switch (lang) {
//            case "kg" -> "❌ Жүктөө катасы";
//            case "en" -> "❌ Upload error";
//            default -> "❌ Ошибка загрузки";
//        };
//
//        String btnBack = switch (lang) {
//            case "kg" -> "Артка";
//            case "en" -> "Back";
//            default -> "Назад";
//        };
//
//        return """
//            <html>
//            <head>
//                <meta name="viewport" content="width=device-width, initial-scale=1.0">
//            </head>
//            <body style="text-align: center; font-family: -apple-system, sans-serif; background-color: #f0f2f5; padding: 20px; margin: 0;">
//                <div style="background: white; padding: 30px 20px; border-radius: 20px; box-shadow: 0 10px 20px rgba(0,0,0,0.05);">
//                    <h2 style="color: #dc3545; margin-top: 0;">%s</h2>
//                    <p style="color: #666; font-size: 16px;">%s</p>
//                    <a href="/upload?lang=%s" style="display: inline-block; margin-top: 20px; padding: 12px 24px; background: #e9ecef; color: #333; text-decoration: none; border-radius: 8px; font-weight: bold;">%s</a>
//                </div>
//            </body>
//            </html>
//            """.formatted(title, message, lang, btnBack);
//    }
//}
