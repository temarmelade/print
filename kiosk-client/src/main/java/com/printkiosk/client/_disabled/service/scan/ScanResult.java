package com.printkiosk.client.service.scan;

import java.util.List;

public record ScanResult(String filePath,
        String fileName,
        String mimeType,
        long fileSizeBytes,
        int pageCount,
        List<ScanPage> pages
) {
}
