package com.printkiosk.server.web.mapper;

import com.printkiosk.server.domain.PrintJobEntity;
import com.printkiosk.shared.api.dto.JobResponse;
import org.springframework.stereotype.Component;

@Component
public class JobMapper {
    public JobResponse toResponse(PrintJobEntity j) {
        // Файл мог быть удалён по TTL (V8: ON DELETE SET NULL) — не падаем на null.
        var fileId = (j.getFile() != null) ? j.getFile().getId() : null;
        return new JobResponse(
                j.getId(), fileId, j.getStatus().name(),
                j.getPriceSom(), j.getPaymentUrl(), j.getCreatedAt()
        );
    }
}
