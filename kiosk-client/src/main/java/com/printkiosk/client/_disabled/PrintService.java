package com.printkiosk.client._disabled;

import com.printkiosk.client.printer.PrintManager;
import com.printkiosk.service.CodeStore;
import com.printkiosk.service.DocumentConversionService;
import com.printkiosk.shared.api.dto.PrintSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PrintService {

    private final PrintManager printManager;
    private final DocumentConversionService documentConversionService;

    public PrintService(PrintManager printManager, DocumentConversionService documentConversionService) {
        this.printManager = printManager;
        this.documentConversionService = documentConversionService;
    }

    public PrintResult executePrint(
            CodeStore.FileSession session,
            PrintSettings settings,
            PrintManager.ProgressCallback progressCallback
    ) {
        if (session == null || session.filePath() == null) {
            log.error("Попытка печати пустой сессии или сессии без filePath!");
            return new PrintResult(false, "Ошибка: файл не найден в системе терминала.");
        }

        if (settings == null) {
            log.warn("PrintSettings is null. Используем настройки по умолчанию.");

            settings = new PrintSettings(
                    1,
                    false,
                    false,
                    "PORTRAIT",
                    "A4"
            );
        }

        try {
            log.info(
                    "Запуск печати: file={}, mime={}, copies={}, color={}, doubleSided={}, orientation={}, paperSize={}",
                    session.filePath(),
                    session.mimeType(),
                    settings.copies(),
                    settings.color(),
                    settings.doubleSided(),
                    settings.orientation(),
                    settings.paperSize()
            );

            String filePath = session.filePath();
            String mimeType = session.mimeType();

            if (documentConversionService.isConvertibleToPdf(mimeType)) {
                progressCallback.update(0.25, "Конвертируем Word-документ в PDF...");

                DocumentConversionService.ConvertedDocument converted =
                        documentConversionService.convertToPdf(filePath);

                filePath = converted.filePath();
                mimeType = converted.mimeType();
            }

            PrintManager.PrintResult result = printManager.printAndDeleteLocalFile(
                    filePath,
                    mimeType,
                    settings,
                    progressCallback
            );

            if (result.success()) {
                progressCallback.update(1.0, "Готово!");
                return new PrintResult(true, "Документ успешно напечатан. Заберите его!");
            }

            String errorMessage = result.errorMessage();

            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = "Печать не выполнена. Проверьте подключение принтера, бумагу и драйвер.";
            }

            return new PrintResult(false, errorMessage);

        } catch (Exception e) {
            log.error("Критический сбой в сервисе печати для файла: {}", session.filePath(), e);
            return new PrintResult(false, "Системная ошибка терминала. Обратитесь к администратору.");
        }
    }

    public record PrintResult(boolean success, String message) {
    }
}