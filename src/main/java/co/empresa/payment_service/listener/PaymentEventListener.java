package co.empresa.payment_service.listener;

import co.empresa.payment_service.config.RabbitMQConfig;
import co.empresa.payment_service.dto.OrderCreatedEvent;
import co.empresa.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final PaymentService paymentService;

    // Referencia la constante del config — si el nombre cambia, compila y falla aquí
    @RabbitListener(queues = RabbitMQConfig.ORDER_CREATED_QUEUE)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("[RabbitMQ] ← OrderCreatedEvent — cartId={} buyerId={} total={}",
                event.getCartId(), event.getBuyerId(), event.getTotal());
        try {
            paymentService.initiatePaymentFromEvent(event);
            log.info("[RabbitMQ] ✓ Preferencia MP creada — cartId={}", event.getCartId());
        } catch (Exception ex) {
            log.error("[RabbitMQ] ✗ Error en cartId={}: {}", event.getCartId(), ex.getMessage());
            // Publica FAILED para que order-service libere el stock
            paymentService.publishFailedResult(event, ex.getMessage());
        }
    }
}