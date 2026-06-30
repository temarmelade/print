package com.printkiosk.client.service;

import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.shared.api.AdSlot;
import com.printkiosk.shared.api.dto.AdCreativeDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdPlaylistService {

    private final KioskServerClient server;

    private final AtomicReference<List<AdCreativeDto>> cache =
            new AtomicReference<>(List.of());

    private ScheduledExecutorService scheduler;

    private static final long REFRESH_INTERVAL_MIN = 5;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ad-playlist-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refresh, 0,
                REFRESH_INTERVAL_MIN, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public List<AdCreativeDto> currentPlaylist() {
        return cache.get();
    }

    private void refresh() {
        try {
            List<AdCreativeDto> fresh = server.adPlaylist(AdSlot.HOME);
            cache.set(fresh != null ? fresh : List.of());
            log.debug("Ad playlist refreshed: {} item(s)", cache.get().size());
        } catch (Exception e) {
            log.warn("Ad playlist refresh failed, keeping cached version: {}",
                    e.getMessage());
        }
    }
}