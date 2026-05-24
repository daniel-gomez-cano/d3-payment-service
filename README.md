### Modificar después
# LO QUE FALTA
```bash
GET  /internal/carts/{cartId}/summary       → devuelve total, ítems, buyerId
POST /internal/carts/{cartId}/checkout      → marca el carrito CHECKED_OUT
POST /internal/carts/{cartId}/paid          → marca el carrito como pagado
POST /internal/carts/{cartId}/payment-failed → libera el carrito si el pago falla
```
payment-service NO nececsita UI, la UI es de mercado pago.

- Prueba de flujo completo en sandbox. Con ambos servicios corriendo, llamas a /initiate, abres el paymentUrl en el navegador, usas las tarjetas de prueba de MercadoPago (te las dan en el panel de desarrolladores), y verificas que el webhook llega y actualiza el estado.

- Conectar al API Gateway. El API Gateway que ya tienen debe enrutar las rutas /api/payments/** hacia el payment-service en el puerto 8084. Igual que lo hace con los otros servicios.
  
-  Prueba de reembolso. Un usuario con rol ORGANIZER llama a POST /api/payments/{id}/refund y verificas que MercadoPago procesa el reembolso en sandbox y el estado cambia a REFUNDED.
  
- Docker Compose completo. Agregar el snippet de docker-compose.snippet.yml al docker-compose principal del proyecto para que todo el sistema levante junto con un solo comando.

# LO QUE FUNCIONA
```bash
Daniel@LAPTOP-IK99S0EA MINGW64 ~
$ curl http://localhost:8084/actuator/health
  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                 Dload  Upload   Total   Spent    Left  Speed
100    15    0    15    0     0   1460      0 --:--:-- --:--:-- --:--:--  1500{"status":"UP"}
Daniel@LAPTOP-IK99S0EA MINGW64 ~
$ curl -s -X POST http://localhost:8084/api/payments/initiate   -H "Content-Type: application/json"   -H "Authorization: Bearer $TOKEN"   -d '{"cartId": "carrito-de-prueba-001"}'
{"error":"Service Unavailable","message":"No se pudo contactar el order-service. Intenta de nuevo.","timestamp":"2026-05-23T22:23:12.158544600","status":503}
Daniel@LAPTOP-IK99S0EA MINGW64 ~
$ curl -s -X POST http://localhost:8084/api/payments/webhook/mercadopago   -H "Content-Type: application/json"   -d '{                                                          "action": "payment.updated",    
"type":"payment",                                                                                                     "data": { "id": "123456789" },                                                "api_version": "v1",                                                         "id": 1,                                                            "live_mode": false,                                                        "user_id": "test"                                                                                                                                                                          }'   -w "\nHTTP Status: %{http_code}"
HTTP Status: 200
```
# ¿qué significa?
Servicio prende sin bugs, se trata de conectar mediante los ednpoints con mercado pago (falla porque falta impl en order-service), pero muestra un 503 en vez de 500, es decir que está controlado, no hay bugs y funciona el código, el status con un webhook simulado es 200, un error controlado también que indica que funciona.

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
