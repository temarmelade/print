package com.printkiosk.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Аутентификация киоска по заголовкам X-Kiosk-Id + X-Kiosk-Key.
 * Успешная проверка даёт роль ROLE_KIOSK — ею закрыты /api/kiosk/**.
 *
 * <p>Не бин: создаётся в SecurityConfig, чтобы Boot не зарегистрировал фильтр
 * повторно на всю цепочку.
 */
public class KioskApiKeyFilter extends OncePerRequestFilter {

    private final KioskAuthService auth;

    public KioskApiKeyFilter(KioskAuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String kioskId = req.getHeader("X-Kiosk-Id");
        String apiKey = req.getHeader("X-Kiosk-Key");

        if (kioskId != null && apiKey != null
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            auth.authenticate(kioskId, apiKey).ifPresent(kiosk -> {
                var token = new UsernamePasswordAuthenticationToken(
                        kiosk.getId(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_KIOSK")));
                SecurityContextHolder.getContext().setAuthentication(token);
            });
        }

        chain.doFilter(req, res);
    }
}
