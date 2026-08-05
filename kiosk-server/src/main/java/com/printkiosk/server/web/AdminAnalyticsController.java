package com.printkiosk.server.web;

import com.printkiosk.server.service.AnalyticsService;
import com.printkiosk.shared.api.dto.AnalyticsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Аналитика по услугам. Как и дашборд, доступна всем вошедшим, но выручку
 * сервер отдаёт ТОЛЬКО владельцу: скрытие на фронте не защищает данные —
 * техник просто открыл бы ответ в DevTools.
 */
@RestController
@RequestMapping("/api/admin/analytics")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AnalyticsService analytics;

    @GetMapping
    public AnalyticsDto analytics(@RequestParam(defaultValue = "30") int days,
                                  Authentication auth) {

        boolean isOwner = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));

        return analytics.analytics(days, isOwner);
    }
}
