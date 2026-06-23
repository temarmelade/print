//package com.printkiosk.server.web;
//
//import com.printkiosk.service.scan.ScannedDocumentStore;
//import lombok.RequiredArgsConstructor;
//import org.springframework.core.io.FileSystemResource;
//import org.springframework.core.io.Resource;
//import org.springframework.http.*;
//import org.springframework.web.bind.annotation.*;
//
//import java.io.File;
//
//@RestController
//@RequiredArgsConstructor
//public class ScanDownloadController {
//
//    private final ScannedDocumentStore scannedDocumentStore;
//
//    @GetMapping("/scan-download/{token}")
//    public ResponseEntity<Resource> downloadScannedFile(@PathVariable String token) {
//        return scannedDocumentStore.get(token)
//                .map(scanResult -> {
//                    File file = new File(scanResult.filePath());
//
//                    if (!file.exists()) {
//                        return ResponseEntity.notFound().<Resource>build();
//                    }
//
//                    Resource resource = new FileSystemResource(file);
//
//                    return ResponseEntity.ok()
//                            .contentType(MediaType.APPLICATION_PDF)
//                            .header(
//                                    HttpHeaders.CONTENT_DISPOSITION,
//                                    "attachment; filename=\"" + scanResult.fileName() + "\""
//                            )
//                            .body(resource);
//                })
//                .orElseGet(() -> ResponseEntity.notFound().build());
//    }
//}
