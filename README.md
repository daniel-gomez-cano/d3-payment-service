# payment-service — VivaEventos

Microservicio de pagos con MercadoPago (sandbox). Puerto: **8084**.

## Requisitos previos
- Java 21, Maven 3.9+
- El `order-service` corriendo en el puerto 8083
- Una cuenta de MercadoPago con Access Token de prueba (sandbox)

## Configuración rápida

```bash
cp env.example .env
# Edita .env y pon tu MP_ACCESS_TOKEN real de sandbox
```

## Correr en local (H2 en memoria)

```bash
mvn spring-boot:run
```

## Correr con Docker

```bash
docker build -t payment-service .
docker run -p 8084:8084 --env-file .env payment-service
```

## Endpoints principales

| Método | Ruta | Descripción |
|--------|------|-------------|
| POST | `/api/payments/initiate` | Inicia el pago de un carrito |
| GET | `/api/payments/cart/{cartId}` | Consulta el pago de un carrito |
| GET | `/api/payments/{paymentId}` | Consulta un pago por ID |
| GET | `/api/payments/my` | Historial de pagos del comprador |
| POST | `/api/payments/{paymentId}/refund` | Reembolso (solo ORGANIZER) |
| GET | `/api/payments/{paymentId}/audit` | Traza de auditoría completa |
| POST | `/api/payments/webhook/mercadopago` | Webhook de MercadoPago (sin JWT) |

## Webhook en desarrollo local con ngrok

MercadoPago necesita una URL pública para enviar webhooks:

```bash
ngrok http 8084
# Copia la URL https://xxx.ngrok-free.app
# Actualiza MP_NOTIFICATION_URL en tu .env
```

## Estados del pago

`PENDING` → `APPROVED` (pago exitoso)  
`PENDING` → `REJECTED` (banco rechazó)  
`PENDING` → `FAILED` (error técnico)  
`APPROVED` → `REFUNDED` (evento cancelado)
