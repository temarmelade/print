package com.printkiosk.server.domain;

import com.printkiosk.shared.api.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    Optional<AdminUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    List<AdminUser> findAllByOrderByCreatedAtAsc();

    long countByRoleAndEnabledTrue(AdminRole role);
}
