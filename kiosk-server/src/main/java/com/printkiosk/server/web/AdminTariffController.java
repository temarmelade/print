package com.printkiosk.server.web;

import com.printkiosk.server.service.TariffService;
import com.printkiosk.shared.api.dto.TariffDto;
import com.printkiosk.shared.api.dto.UpdateTariffRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Модуль «Цены». Тариф — это деньги, поэтому и смотреть, и менять может
 * только владелец: техник с доступом к киоскам не должен иметь возможности
 * переставить прайс, а поддержка видит фактические суммы в транзакциях.
 */
@RestController
@RequestMapping("/api/admin/tariffs")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class AdminTariffController {

    private final TariffService tariffs;

    /** Действующие цены: глобальная (kioskId = null) + переопределения. */
    @GetMapping
    public List<TariffDto> current() {
        return tariffs.listCurrent();
    }

    /** История цен. Без параметра — история глобального дефолта. */
    @GetMapping("/history")
    public List<TariffDto> history(@RequestParam(value = "kioskId", required = false) String kioskId) {
        return tariffs.history(kioskId);
    }

    /** Смена глобальной цены — действует на все киоски без своей. */
    @PutMapping("/default")
    public TariffDto updateDefault(@Valid @RequestBody UpdateTariffRequest req) {
        return tariffs.setPrice(null, req.bwPriceSom(), req.colorPriceSom());
    }

    /** Персональная цена киоска (например, в аренде дороже). */
    @PutMapping("/{kioskId}")
    public TariffDto updateForKiosk(@PathVariable String kioskId,
                                    @Valid @RequestBody UpdateTariffRequest req) {
        return tariffs.setPrice(kioskId, req.bwPriceSom(), req.colorPriceSom());
    }

    /** Снять персональную цену — киоск вернётся на глобальный дефолт. */
    @DeleteMapping("/{kioskId}")
    public ResponseEntity<Void> resetToDefault(@PathVariable String kioskId) {
        tariffs.resetToDefault(kioskId);
        return ResponseEntity.noContent().build();
    }
}
