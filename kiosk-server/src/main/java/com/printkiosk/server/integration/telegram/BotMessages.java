package com.printkiosk.server.integration.telegram;

import org.springframework.stereotype.Component;

@Component
public class BotMessages {

    public String welcome() {
        return """
                Привет! / Салам! / Hello!

                Выберите язык / Тилди тандаңыз / Choose language:
                🇷🇺 /ru — Русский
                🇰🇬 /kg — Кыргызча
                🇬🇧 /en — English

                Затем отправьте PDF, DOCX или фото для печати.""";
    }

    public String languageChanged(String lang) {
        return switch (lang) {
            case "kg" -> "🇰🇬 Тил Кыргызчага өзгөртүлдү!";
            case "en" -> "🇬🇧 Language changed to English!";
            default   -> "🇷🇺 Язык изменён на русский!";
        };
    }

    public String uploading(String lang) {
        return switch (lang) {
            case "kg" -> "⏳ Файл жүктөлүүдө...";
            case "en" -> "⏳ Uploading file...";
            default   -> "⏳ Загружаю файл...";
        };
    }

    public String uploadSuccess(String lang, String pin) {
        return switch (lang) {
            case "kg" -> "✅ Даяр! Кодуңуз: *" + pin + "*\nКиосктун экранына киргизиңиз.";
            case "en" -> "✅ Ready! Your code: *" + pin + "*\nEnter it on the kiosk screen.";
            default   -> "✅ Готово! Ваш код: *" + pin + "*\nВведите его на экране киоска.";
        };
    }

    public String uploadFailed(String lang) {
        return switch (lang) {
            case "kg" -> "❌ Файлды иштетүүдө ката кетти.";
            case "en" -> "❌ File processing failed.";
            default   -> "❌ Не удалось обработать файл.";
        };
    }

    public String unsupportedContent(String lang) {
        return switch (lang) {
            case "kg" -> "Мага PDF, DOCX же сүрөт жөнөтүңүз.";
            case "en" -> "Send me a PDF, DOCX, or photo.";
            default   -> "Отправьте мне PDF, DOCX или фото.";
        };
    }

    public String howToUse(String lang) { return unsupportedContent(lang); }
    public String genericError(String lang) { return uploadFailed(lang); }
}