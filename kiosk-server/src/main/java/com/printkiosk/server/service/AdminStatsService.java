//package com.printkiosk.server.service;
//
//import com.printkiosk.shared.api.OperationType;
//import com.printkiosk.server.domain.PrintJobRepository;
//import com.printkiosk.shared.api.dto.AdminStatsDto;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Service;
//
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//
//@Service
//@RequiredArgsConstructor
//public class AdminStatsService {
//
//    private final PrintJobRepository printJobRepository;
//
//    public AdminStatsDto getTodayStats() {
//        LocalDate today = LocalDate.now();
//
//        LocalDateTime start = today.atStartOfDay();
//        LocalDateTime end = today.plusDays(1).atStartOfDay();
//
//        long totalJobs = printJobRepository.countJobsBetween(start, end);
//
//        long printJobs = printJobRepository.countByOperationTypeBetween(
//                OperationType.PRINT,
//                start,
//                end
//        );
//
//        long copyJobs = printJobRepository.countByOperationTypeBetween(
//                OperationType.COPY,
//                start,
//                end
//        );
//
//        long scanPrintJobs = printJobRepository.countByOperationTypeBetween(
//                OperationType.SCAN_PRINT,
//                start,
//                end
//        );
//
//        long scanWebJobs = printJobRepository.countByOperationTypeBetween(
//                OperationType.SCAN_DOWNLOAD_WEB,
//                start,
//                end
//        );
//
//        long scanTelegramJobs = printJobRepository.countByOperationTypeBetween(
//                OperationType.SCAN_SEND_TELEGRAM,
//                start,
//                end
//        );
//
//        long scanJobs = scanPrintJobs + scanWebJobs + scanTelegramJobs;
//
//        long failedJobs = printJobRepository.countFailedBetween(start, end);
//
//        int revenue = printJobRepository.sumRevenueBetween(start, end);
//
//        return new AdminStatsDto(
//                totalJobs,
//                printJobs,
//                copyJobs,
//                scanJobs,
//                failedJobs,
//                revenue
//        );
//    }
//}
