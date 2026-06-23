package com.printkiosk.client.util;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Сетевые утилиты. Сейчас — определение локального IP киоска для построения
 * QR-ссылок (web-upload, scan-download), которые открываются с телефонов
 * пользователей по локальной сети.
 */
@Slf4j
public final class NetworkUtils {

    private static final String FALLBACK_IP = "127.0.0.1";

    private NetworkUtils() {
        // utility class
    }

    /**
     * Возвращает первый внешний (не loopback, не виртуальный) IPv4-адрес.
     * Если ничего не найдено — {@value #FALLBACK_IP}.
     */
    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                if (networkInterface.isLoopback()
                        || !networkInterface.isUp()
                        || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to determine local IP, falling back to {}", FALLBACK_IP, e);
        }

        return FALLBACK_IP;
    }
}
