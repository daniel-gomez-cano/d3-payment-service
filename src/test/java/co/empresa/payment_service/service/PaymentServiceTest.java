package co.empresa.payment_service.service;

import co.empresa.payment_service.config.OrderServiceClient;
import co.empresa.payment_service.dto.AuditLogResponse;
import co.empresa.payment_service.dto.InitiatePaymentRequest;
import co.empresa.payment_service.dto.PaymentResponse;
import co.empresa.payment_service.model.Payment;
import co.empresa.payment_service.model.PaymentAuditLog;
import co.empresa.payment_service.model.PaymentStatus;
import co.empresa.payment_service.repository.PaymentAuditLogRepository;
import co.empresa.payment_service.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepo;

    @Mock
    private PaymentAuditLogRepository auditRepo;

    @Mock
    private OrderServiceClient orderClient;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void initiatePayment_returnsExistingPayment_whenIdempotencyKeyAlreadyExists() {
        Payment existing = Payment.builder()
                .id("pay-1")
                .cartId("cart-1")
                .buyerId("buyer-1")
                .amount(BigDecimal.valueOf(120000))
                .currency("COP")
                .status(PaymentStatus.PENDING)
                .paymentUrl("https://mp.test/checkout")
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRepo.findByIdempotencyKey("cart-1")).thenReturn(Optional.of(existing));

        InitiatePaymentRequest req = new InitiatePaymentRequest();
        req.setCartId("cart-1");

        PaymentResponse response = paymentService.initiatePayment(req, "buyer-1", "jwt-token");

        assertNotNull(response);
        assertEquals("pay-1", response.getPaymentId());
        assertEquals("cart-1", response.getCartId());
        assertEquals(PaymentStatus.PENDING, response.getStatus());

        verify(orderClient, never()).getCartSummary(anyString(), anyString());
        verify(paymentRepo, never()).save(org.mockito.ArgumentMatchers.any(Payment.class));
    }

    @Test
    void getPaymentById_throwsForbidden_whenPaymentBelongsToOtherUser() {
        Payment payment = Payment.builder()
                .id("pay-2")
                .buyerId("owner-user")
                .status(PaymentStatus.PENDING)
                .amount(BigDecimal.TEN)
                .currency("COP")
                .build();

        when(paymentRepo.findById("pay-2")).thenReturn(Optional.of(payment));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.getPaymentById("pay-2", "another-user"));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatusCode().value());
    }

    @Test
    void getPaymentById_throwsNotFound_whenPaymentDoesNotExist() {
        when(paymentRepo.findById("missing-pay")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.getPaymentById("missing-pay", "buyer-1"));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getStatusCode().value());
    }

    @Test
    void getAuditLog_returnsMappedEntries_forOwnerUser() {
        Payment payment = Payment.builder()
                .id("pay-3")
                .buyerId("buyer-3")
                .build();

        PaymentAuditLog log = PaymentAuditLog.builder()
                .id("log-1")
                .paymentId("pay-3")
                .previousStatus(PaymentStatus.PENDING)
                .newStatus(PaymentStatus.APPROVED)
                .actor("WEBHOOK:mercadopago")
                .detail("approved")
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRepo.findById("pay-3")).thenReturn(Optional.of(payment));
        when(auditRepo.findByPaymentIdOrderByCreatedAtAsc("pay-3")).thenReturn(List.of(log));

        List<AuditLogResponse> response = paymentService.getAuditLog("pay-3", "buyer-3");

        assertEquals(1, response.size());
        assertEquals("log-1", response.get(0).getId());
        assertEquals(PaymentStatus.APPROVED, response.get(0).getNewStatus());
    }
}