package co.empresa.payment_service.controller;

import co.empresa.payment_service.dto.AuditLogResponse;
import co.empresa.payment_service.dto.InitiatePaymentRequest;
import co.empresa.payment_service.dto.PaymentResponse;
import co.empresa.payment_service.dto.RefundRequest;
import co.empresa.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints principales del payment-service.
 *
 * Base path: /api/payments
 *
 * POST   /api/payments/initiate            → inicia el pago de un carrito (Stripe Checkout)
 * GET    /api/payments/cart/{cartId}       → consulta el pago asociado a un carrito
 * GET    /api/payments/{paymentId}         → consulta un pago por su ID interno
 * GET    /api/payments/my                 → historial de pagos del comprador autenticado
 * POST   /api/payments/{paymentId}/refund  → reembolso (solo ORGANIZER)
 * GET    /api/payments/{paymentId}/audit   → traza de auditoría completa
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Inicia el proceso de pago para un carrito.
     * Devuelve la URL de Stripe Checkout a la que el frontend debe redirigir al usuario.
     *
     * Idempotente: si el carrito ya tiene un pago PENDING, devuelve el existente.
     */
    @PostMapping("/initiate")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String buyerId  = jwt.getSubject();
        String jwtToken = jwt.getTokenValue();
        return paymentService.initiatePayment(request, buyerId, jwtToken);
    }

    /**
     * Consulta el pago asociado a un carrito.
     * Útil para que el frontend sepa si el pago ya fue procesado al volver del success_url.
     */
    @GetMapping("/cart/{cartId}")
    public PaymentResponse getByCartId(
            @PathVariable String cartId,
            @AuthenticationPrincipal Jwt jwt) {

        return paymentService.getPaymentByCartId(cartId, jwt.getSubject());
    }

    /**
     * Consulta un pago por su ID interno.
     */
    @GetMapping("/{paymentId}")
    public PaymentResponse getById(
            @PathVariable String paymentId,
            @AuthenticationPrincipal Jwt jwt) {

        return paymentService.getPaymentById(paymentId, jwt.getSubject());
    }

    /**
     * Devuelve todos los pagos del comprador autenticado, ordenados por fecha descendente.
     */
    @GetMapping("/my")
    public List<PaymentResponse> getMyPayments(@AuthenticationPrincipal Jwt jwt) {
        return paymentService.getMyPayments(jwt.getSubject());
    }

    /**
     * Solicita el reembolso de un pago aprobado.
     * Solo accesible por usuarios con rol ORGANIZER.
     * Se usa cuando el organizador cancela un evento.
     */
    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasAuthority('ORGANIZER')")
    public PaymentResponse refund(
            @PathVariable String paymentId,
            @Valid @RequestBody RefundRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        return paymentService.refundPayment(paymentId, request, jwt.getSubject());
    }

    /**
     * Devuelve la traza de auditoría completa de un pago.
     * Muestra todos los cambios de estado con actor, timestamp y detalle.
     */
    @GetMapping("/{paymentId}/audit")
    public ResponseEntity<List<AuditLogResponse>> getAuditLog(
            @PathVariable String paymentId,
            @AuthenticationPrincipal Jwt jwt) {

        List<AuditLogResponse> logs = paymentService.getAuditLog(paymentId, jwt.getSubject());
        return ResponseEntity.ok(logs);
    }
}