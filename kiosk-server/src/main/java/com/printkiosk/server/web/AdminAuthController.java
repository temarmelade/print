package com.printkiosk.server.web;

import com.printkiosk.server.domain.AdminUser;
import com.printkiosk.server.security.AdminPrincipal;
import com.printkiosk.server.service.AdminUserService;
import com.printkiosk.server.service.JwtService;
import com.printkiosk.shared.api.dto.AdminUserDto;
import com.printkiosk.shared.api.dto.LoginRequest;
import com.printkiosk.shared.api.dto.LoginResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Вход в админ-панель и профиль текущего сотрудника.
 *   POST /api/admin/auth/login  — открыт (см. SecurityConfig)
 *   GET  /api/admin/auth/me     — требует токен
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminUserService users;
    private final JwtService jwt;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        AdminUser user = users.authenticate(req.username(), req.password());
        String token = jwt.issue(user);
        return new LoginResponse(token, AdminUserService.toDto(user));
    }

    @GetMapping("/me")
    public AdminUserDto me(@AuthenticationPrincipal AdminPrincipal principal) {
        return new AdminUserDto(principal.id(), principal.name(), principal.username(),
                principal.role(), true, null);
    }
}
