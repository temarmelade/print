package com.printkiosk.server.web.mapper;

import com.printkiosk.server.domain.PrintJobEntity;
import com.printkiosk.shared.api.dto.JobResponse;
import org.springframework.stereotype.Component;

@Component
public class JobMapper {
    public JobResponse toResponse(PrintJobEntity j) {
        return new JobResponse(
                j.getId(), j.getFile().getId(), j.getStatus().name(),
                j.getPriceSom(), j.getPaymentUrl(), j.getCreatedAt()
        );
    }
}
