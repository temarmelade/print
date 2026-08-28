package com.printkiosk.server.security;

import com.printkiosk.server.config.AdminProperties;
import com.printkiosk.server.service.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Безопасность API.
 *
 * <p>Принцип: открыт только тот маршрут, который открывает браузер
 * постороннего человека. Всё, что вызывает киоск, требует X-Kiosk-Key.
 * Раньше открытым было всё, кроме админки и телеметрии, — то есть любой
 * желающий мог пометить чужое задание выполненным или создать платёж.
 *
 * <pre>
 *   ОТКРЫТО (телефон/браузер посетителя):
 *     /api/admin/auth/login       вход в админку
 *     /api/files/upload           веб-портал загрузки
 *     /api/files/d/**             скачивание по одноразовому токену
 *     /api/payments/webhook/**    колбэк платёжного провайдера
 *
 *   ТОЛЬКО КИОСК (X-Kiosk-Key):
 *     /api/kiosk/**, /api/files/**, /api/jobs/**,
 *     /api/payments/**, /api/scan-delivery/**, /api/ads/playlist
 *
 *   АДМИНКА (JWT):
 *     /api/ads/admin/**  — OWNER
 *     /api/admin/**      — любой вошедший, роли уточняет @PreAuthorize
 * </pre>
 *
 * Stateless (JWT), поэтому CSRF отключён, сессии не создаются.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtService jwtService,
                                           KioskAuthService kioskAuth,
                                           RestAuthEntryPoint authEntryPoint,
                                           RestAccessDeniedHandler accessDeniedHandler,
                                           AdminProperties props) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService);
        KioskApiKeyFilter kioskFilter = new KioskApiKeyFilter(kioskAuth);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsSource(props)))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ── Открытые маршруты ──
                        .requestMatchers("/api/admin/auth/login").permitAll()
                        // Веб-портал загрузки: открывает посетитель с телефона,
                        // ключа киоска у него нет и быть не может.
                        .requestMatchers(HttpMethod.POST, "/api/files/upload").permitAll()
                        // Скачивание по одноразовому токену (32 байта в ссылке).
                        .requestMatchers(HttpMethod.GET, "/api/files/d/**").permitAll()
                        // Колбэк платёжного провайдера: приходит извне,
                        // подлинность проверяется подписью в самом обработчике.
                        .requestMatchers("/api/payments/webhook/**").permitAll()

                        // ── Админка ──
                        .requestMatchers("/api/ads/admin/**").hasRole("OWNER")
                        .requestMatchers("/api/admin/**").authenticated()

                        // ── Всё остальное API — только киоск ──
                        .requestMatchers("/api/kiosk/**").hasRole("KIOSK")
                        .requestMatchers("/api/files/**").hasRole("KIOSK")
                        .requestMatchers("/api/jobs/**").hasRole("KIOSK")
                        .requestMatchers("/api/payments/**").hasRole("KIOSK")
                        .requestMatchers("/api/scan-delivery/**").hasRole("KIOSK")
                        .requestMatchers("/api/ads/playlist").hasRole("KIOSK")

                        // Статика (страница загрузки, файлы для печати) и
                        // всё вне /api остаются открытыми.
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(kioskFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsSource(AdminProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.getCors().getAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // CORS нужен только админским роутам; на остальные не навешиваем.
        source.registerCorsConfiguration("/api/admin/**", cfg);
        source.registerCorsConfiguration("/api/ads/admin/**", cfg);
        return source;
    }
}
