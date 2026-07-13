package com.printkiosk.server.web;

import com.printkiosk.server.service.DashboardService;
import com.printkiosk.shared.api.dto.DashboardDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Сводка для дашборда. Доступна всем вошедшим, но выручку сервер отдаёт
 * ТОЛЬКО владельцу — не полагаемся на то, что SPA её спрячет: скрытие на
 * фронте не защищает данные, техник просто открыл бы ответ в DevTools.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboard;

    @GetMapping
    public DashboardDto summary(@RequestParam(defaultValue = "30") int days,
                                Authentication auth) {

        boolean isOwner = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));

        return dashboard.summary(days, isOwner);
    }
}
