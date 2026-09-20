package com.printkiosk.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Адрес сервера, каким его видит ТЕЛЕФОН посетителя. Из него строятся все
 * ссылки, которые попадают в QR-коды: страница загрузки файлов и получение
 * сканов.
 *
 * <p>Раньше каждый киоск хранил свою копию этого адреса, и копии расходились:
 * QR загрузки вёл на вшитый в код {@code http://192.168.1.120/upload} (без
 * порта и с устаревшим IP). Теперь адрес знает только сервер, а киоск
 * запрашивает у него готовую ссылку.
 *
 * <p>Правила:
 * <ul>
 *   <li>{@code kiosk.storage.public-base-url} задан и это не localhost —
 *       берём как есть. Так работает прод: {@code PUBLIC_BASE_URL} с доменом
 *       за Cloudflare.</li>
 *   <li>Не задан или указывает на localhost — сервер запущен в локальной
 *       сети, и телефон по localhost не достучится. Тогда определяем
 *       LAN-адрес машины сами: {@code http://<IP>:<server.port>}. Адрес
 *       перепроверяется раз в минуту, так что смена IP по DHCP подхватится
 *       без перезапуска.</li>
 * </ul>
 */
@Slf4j
@Component
public class PublicUrlResolver {

    /** Как часто в автоматическом режиме перепроверяем IP машины. */
    private static final long AUTO_RECHECK_MS = 60_000;

    /**
     * Признаки виртуальных адаптеров. Их адреса телефону недоступны, а на
     * Windows они почти всегда есть (Hyper-V, WSL, VirtualBox, VPN).
     */
    private static final List<String> VIRTUAL_MARKERS = List.of(
            "virtual", "vmware", "vbox", "hyper-v", "vethernet", "wsl",
            "docker", "loopback", "tap-", "vpn", "tailscale", "zerotier",
            "hamachi", "radmin", "bluetooth", "npcap", "veth", "br-");

    private final String configured;
    private final boolean auto;
    private final int port;

    private volatile String cached;
    private volatile long cachedAtMs;

    public PublicUrlResolver(KioskServerProperties properties,
                             @Value("${server.port:8080}") int port) {
        this.configured = stripTrailingSlash(properties.getStorage().getPublicBaseUrl());
        this.auto = isLocalOnly(this.configured);
        this.port = port;
    }

    /** Базовый адрес для телефона, без завершающего «/». */
    public String phoneBaseUrl() {
        if (!auto) return configured;

        long now = System.currentTimeMillis();
        String current = cached;
        if (current != null && now - cachedAtMs < AUTO_RECHECK_MS) return current;

        String detected = detectLanAddress();
        String result = detected != null ? "http://" + detected + ":" + port : configured;
        if (!result.equals(current)) {
            if (detected != null) {
                log.info("Адрес для телефонов определён автоматически: {}", result);
            } else {
                log.warn("Не удалось определить IP в локальной сети. Ссылки в QR "
                        + "ведут на {} — телефон их не откроет. Задайте "
                        + "KIOSK_PUBLIC_BASE_URL.", result);
            }
        }
        cached = result;
        cachedAtMs = now;
        return result;
    }

    /**
     * Ссылка на страницу загрузки для конкретного терминала. Параметр
     * {@code k} страница показывает посетителю («Терминал: …») и передаёт
     * обратно при загрузке.
     */
    public String uploadPageUrl(String kioskId) {
        return phoneBaseUrl() + "/upload?k="
                + URLEncoder.encode(kioskId, StandardCharsets.UTF_8);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logOnStartup() {
        log.info("Страница загрузки для телефонов: {}/upload", phoneBaseUrl());
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутреннее
    // ════════════════════════════════════════════════════════════════

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** true — адрес пустой или указывает на саму машину. */
    static boolean isLocalOnly(String url) {
        if (url == null || url.isBlank()) return true;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            // Нестандартный адрес лучше отдать как есть, чем молча подменить.
            return false;
        }
        if (host == null) return true;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("localhost")
                || host.startsWith("127.")
                || host.equals("0.0.0.0")
                || host.equals("[::1]")
                || host.equals("::1");
    }

    /**
     * IPv4 из частных диапазонов на физическом адаптере. При нескольких
     * кандидатах предпочитаем 192.168.x.x (типичный Wi-Fi-роутер), затем
     * 10.x.x.x, и лишь потом 172.16–31.x.x — там обычно сети Docker и WSL.
     */
    static String detectLanAddress() {
        String best = null;
        int bestScore = 0;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()
                        || nif.isPointToPoint() || looksVirtual(nif)) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress()) continue;
                    int score = score(addr);
                    if (score > bestScore) {
                        best = addr.getHostAddress();
                        bestScore = score;
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("Не удалось перечислить сетевые интерфейсы: {}", e.getMessage());
        }
        return best;
    }

    private static boolean looksVirtual(NetworkInterface nif) {
        String label = (nif.getName() + " " + Objects.toString(nif.getDisplayName(), ""))
                .toLowerCase(Locale.ROOT);
        return VIRTUAL_MARKERS.stream().anyMatch(label::contains);
    }

    private static int score(InetAddress addr) {
        byte[] b = addr.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        if (first == 192 && second == 168) return 3;
        if (first == 10) return 2;
        return 1;   // 172.16.0.0/12
    }
}
