package co.empresa.payment_service.dto;

import co.empresa.payment_service.model.PaymentAuditLog;
import co.empresa.payment_service.model.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Representación de un registro de auditoría para exponer vía API.
 */
@Data
@Builder
public class AuditLogResponse {

    private String id;
    private PaymentStatus previousStatus;
    private PaymentStatus newStatus;
    private String actor;
    private String detail;
    private LocalDateTime createdAt;

    public static AuditLogResponse from(PaymentAuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .previousStatus(log.getPreviousStatus())
                .newStatus(log.getNewStatus())
                .actor(log.getActor())
                .detail(log.getDetail())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
