package com.printkiosk.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Кладёт kiosk_id и request_id в MDC на время обработки запроса.
 * Все log-вызовы внутри обработки автоматически получают эти поля
 * в выводе (если в logback-конфиге используется %X{kioskId} / %X{requestId}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter extends OncePerRequestFilter {

    private static final String KIOSK_ID_HEADER  = "X-Kiosk-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KIOSK_ID     = "kioskId";
    private static final String MDC_REQUEST_ID   = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String kioskId   = req.getHeader(KIOSK_ID_HEADER);
        String requestId = req.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        try {
            if (kioskId != null && !kioskId.isBlank()) {
                MDC.put(MDC_KIOSK_ID, kioskId);
            }
            MDC.put(MDC_REQUEST_ID, requestId);

            // Пробрасываем requestId обратно — клиент сможет залогировать
            // тот же id у себя и связать концы.
            res.setHeader(REQUEST_ID_HEADER, requestId);

            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KIOSK_ID);
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
