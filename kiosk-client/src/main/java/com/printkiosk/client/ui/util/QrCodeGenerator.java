package com.printkiosk.client.ui.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
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
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size);

            WritableImage qrImage = new WritableImage(size, size);
            var pixelWriter = qrImage.getPixelWriter();

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    pixelWriter.setColor(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return qrImage;

        } catch (WriterException e) {
            log.error("QR generation failed for text of length {}", text.length(), e);
            return null;
        }
    }
}
