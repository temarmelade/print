package com.printkiosk.client.ui.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import lombok.extern.slf4j.Slf4j;

/**
 * Генерация QR-кодов в JavaFX {@link Image}.
 *
 * <p>В отличие от старого кода в контроллере, не принимает {@code ImageView} —
 * возвращает {@link Image}, который вызывающий сам ставит в нужный виджет.
 * Так утилита не зависит от конкретных UI-элементов.
 *
 * <p>Возвращает {@code null} при ошибке кодирования (например, слишком длинный
 * текст для запрошенного размера) — вызывающий решает, что показать.
 */
@Slf4j
public final class QrCodeGenerator {

    /**
     * Цвет модулей. Тёмно-синий из палитры интерфейса вместо чистого
     * чёрного — код перестаёт выбиваться из оформления экрана.
     *
     * <p>Светлее делать нельзя: сканеры ориентируются на контраст с фоном,
     * и на бледном коде распознавание начинает зависеть от освещения в
     * зале. Этот оттенок достаточно тёмный, чтобы контраст остался
     * заведомо избыточным.
     */
    private static final Color MODULE_COLOR = Color.web("#0B2A5B");

    /**
     * Уровень коррекции ошибок M: код переживает до 15% повреждений.
     * Нужен из-за бликов на глянцевом экране киоска — по умолчанию
     * ZXing ставит L, и часть телефонов на солнце код не ловит.
     */
    private static final ErrorCorrectionLevel EC_LEVEL = ErrorCorrectionLevel.M;

    private QrCodeGenerator() {
        // utility class
    }

    /**
     * Кодирует {@code text} в QR-код размером {@code size}×{@code size} пикселей.
     *
     * @return изображение QR-кода или {@code null}, если кодирование не удалось
     */
    public static Image generate(String text, int size) {
        if (text == null || text.isBlank()) {
            log.warn("QR generation skipped: empty text");
            return null;
        }
        if (size <= 0) {
            log.warn("QR generation skipped: non-positive size {}", size);
            return null;
        }

        try {
            var hints = new java.util.EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, EC_LEVEL);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            // Тихая зона в 2 модуля. Нулевая ломает распознавание, а
            // стандартные 4 съедают заметную часть картинки на экране.
            hints.put(EncodeHintType.MARGIN, 2);

            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix =
                    qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size, hints);

            WritableImage qrImage = new WritableImage(size, size);
            var pixelWriter = qrImage.getPixelWriter();

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    // Фон прозрачный: карточка под кодом уже белая, а
                    // непрозрачный квадрат давал видимый шов по краям.
                    pixelWriter.setColor(x, y,
                            bitMatrix.get(x, y) ? MODULE_COLOR : Color.TRANSPARENT);
                }
            }

            return qrImage;

        } catch (WriterException e) {
            log.error("QR generation failed for text of length {}", text.length(), e);
            return null;
        }
    }
}
