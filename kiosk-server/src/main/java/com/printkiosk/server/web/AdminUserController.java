package com.printkiosk.server.web;

import com.printkiosk.server.service.AdminUserService;
import com.printkiosk.shared.api.AdminRole;
import com.printkiosk.shared.api.dto.AdminUserDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Управление сотрудниками (модуль «Доступы»). Только владелец.
 *   GET    /api/admin/users
 *   POST   /api/admin/users
 *   PATCH  /api/admin/users/{id}/role
 *   PATCH  /api/admin/users/{id}/enabled
 *   POST   /api/admin/users/{id}/reset-password
 *   DELETE /api/admin/users/{id}
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService users;

    @GetMapping
    public List<AdminUserDto> list() {
        return users.list();
    }

    @PostMapping
    public AdminUserDto create(@Valid @RequestBody CreateUserRequest req) {
        return users.create(req.name(), req.username(), req.password(), req.role());
    }

    @PatchMapping("/{id}/role")
    public AdminUserDto changeRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest req) {
        return users.changeRole(id, req.role());
    }

    @PatchMapping("/{id}/enabled")
    public AdminUserDto setEnabled(@PathVariable UUID id, @RequestParam boolean enabled) {
        return users.setEnabled(id, enabled);
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody PasswordRequest req) {
        users.resetPassword(id, req.password());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        users.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── тела запросов ──
    public record CreateUserRequest(
            @NotBlank String name,
            @NotBlank String username,
            @NotBlank String password,
            @NotNull AdminRole role) {}

    public record RoleRequest(@NotNull AdminRole role) {}

    public record PasswordRequest(@NotBlank String password) {}
}
