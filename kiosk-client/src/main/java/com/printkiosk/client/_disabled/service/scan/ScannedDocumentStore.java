package com.printkiosk.client.service.scan;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ScannedDocumentStore {

    private static final long TTL_SECONDS = 10 * 60;

    private final ConcurrentHashMap<String, StoredScannedDocument> store = new ConcurrentHashMap<>();

    public String register(ScanResult scanResult) {
        String token = UUID.randomUUID().toString();

        store.put(
                token,
                new StoredScannedDocument(
                        scanResult,
                        Instant.now().plusSeconds(TTL_SECONDS)
                )
        );

        return token;
    }

    public Optional<ScanResult> get(String token) {
        StoredScannedDocument stored = store.get(token);

        if (stored == null || Instant.now().isAfter(stored.expiresAt())) {
            store.remove(token);
            return Optional.empty();
        }

        return Optional.of(stored.scanResult());
    }

    public record StoredScannedDocument(
            ScanResult scanResult,
            Instant expiresAt
    ) {
    }
}
