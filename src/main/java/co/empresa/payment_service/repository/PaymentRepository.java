package co.empresa.payment_service.repository;

import co.empresa.payment_service.model.Payment;
import co.empresa.payment_service.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    /** Busca por clave de idempotencia para evitar cobros duplicados. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** Busca por cartId para auditoría o re-consulta. */
    Optional<Payment> findByCartId(String cartId);

    /** Historial de pagos de un comprador. */
    List<Payment> findByBuyerIdOrderByCreatedAtDesc(String buyerId);

    /** Todos los pagos de un comprador con un estado específico. */
    List<Payment> findByBuyerIdAndStatus(String buyerId, PaymentStatus status);

    Optional<Payment> findByPaymentIntentId(String paymentIntentId);
}
