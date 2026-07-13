package com.printkiosk.server.security;

import com.printkiosk.server.service.JwtService;
import com.printkiosk.shared.api.AdminRole;
import io.jsonwebtoken.Claims;
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
import java.util.UUID;

/**
 * Читает Bearer-токен, проверяет подпись и кладёт аутентификацию в контекст.
 * Невалидный/отсутствующий токен — просто идём дальше без аутентификации;
 * защищённые роуты потом отсечёт entry point (401).
 *
 * Не бин: создаётся в SecurityConfig, чтобы Spring Boot не зарегистрировал
 * его вторично как обычный сервлет-фильтр на всю цепочку.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwt.parse(header.substring(7)).getPayload();
                String role = c.get("role", String.class);
                AdminPrincipal principal = new AdminPrincipal(
                        UUID.fromString(c.get("uid", String.class)),
                        c.getSubject(),
                        c.get("name", String.class),
                        AdminRole.valueOf(role));

                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // битый токен — оставляем анонимным
            }
        }
        chain.doFilter(req, res);
    }
}
