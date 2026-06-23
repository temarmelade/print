package com.printkiosk.shared.api.dto;

public record AdminStatsDto(
        long totalJobsToday,
        long printJobsToday,
        long copyJobsToday,
        long scanJobsToday,
        long failedJobsToday,
        int totalRevenueToday
) {
}
