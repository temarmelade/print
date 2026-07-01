package com.printkiosk.client.service.scan;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public interface ScannerService {
    CompletableFuture<File> scanPage();
    CompletableFuture<Boolean> isReady();
}