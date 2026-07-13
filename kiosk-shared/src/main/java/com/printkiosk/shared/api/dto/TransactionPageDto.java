package com.printkiosk.shared.api.dto;

import java.util.List;

/**
 * Страница транзакций + сводка по ВСЕЙ выборке (не только по текущей странице),
 * чтобы «Итого» в шапке таблицы не врало при листании.
 */
public record TransactionPageDto(
        List<TransactionDto> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        long paidCount,
        long revenueSom
) {}
