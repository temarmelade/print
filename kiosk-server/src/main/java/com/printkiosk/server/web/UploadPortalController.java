package com.printkiosk.server.web;

import com.printkiosk.server.config.PublicUrlResolver;
import com.printkiosk.server.domain.KioskEntity;
import com.printkiosk.server.domain.KioskRepository;
import com.printkiosk.shared.api.dto.UploadLinkResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * Связка «QR на экране киоска → страница загрузки на телефоне».
 *
 * <ul>
 *   <li>{@code GET /api/kiosk/upload-link} — киоск забирает готовую ссылку
 *       для QR. Адрес знает только сервер, поэтому у киосков больше нет
 *       своих копий IP/домена, которые расходятся с реальностью.</li>
 *   <li>{@code GET /api/files/upload/terminal?k=} — страница загрузки
 *       узнаёт, для какого терминала её открыли, и показывает посетителю
 *       его название.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class UploadPortalController {

    /** Формат идентификатора киоска; всё прочее даже не ищем в базе. */
    static final Pattern KIOSK_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final PublicUrlResolver publicUrls;
    private final KioskRepository kiosks;

    /**
     * Ссылка для QR загрузки. kioskId берётся из аутентификации
     * (X-Kiosk-Id + X-Kiosk-Key), а не из запроса — киоск не может
     * получить ссылку от имени другого терминала.
     */
    @GetMapping("/api/kiosk/upload-link")
    @PreAuthorize("hasRole('KIOSK')")
    public UploadLinkResponse uploadLink(@AuthenticationPrincipal String kioskId) {
        return new UploadLinkResponse(publicUrls.uploadPageUrl(kioskId));
    }

    /**
     * Название и расположение терминала для шапки страницы. Открытый
     * эндпоинт: вызывает браузер посетителя. Отдаём только то, что и так
     * написано на корпусе киоска; отключённые терминалы не показываем.
     */
    @GetMapping("/api/files/upload/terminal")
    public ResponseEntity<TerminalInfo> terminal(@RequestParam("k") String kioskId) {
        if (!KIOSK_ID.matcher(kioskId).matches()) {
            return ResponseEntity.notFound().build();
        }
        return kiosks.findById(kioskId)
                .filter(KioskEntity::isEnabled)
                .map(k -> ResponseEntity.ok(new TerminalInfo(k.getName(), k.getLocation())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record TerminalInfo(String name, String location) {}
}
