package co.empresa.payment_service.repository;

import co.empresa.payment_service.model.PaymentAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAuditLogRepository extends JpaRepository<PaymentAuditLog, String> {

    /** Obtiene toda la traza de cambios de un pago, en orden cronológico. */
    List<PaymentAuditLog> findByPaymentIdOrderByCreatedAtAsc(String paymentId);
}
