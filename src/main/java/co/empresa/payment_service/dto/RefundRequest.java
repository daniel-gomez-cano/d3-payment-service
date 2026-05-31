package co.empresa.payment_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Petición de reembolso enviada por el organizador o el sistema
 * cuando un evento es cancelado.
 */
@Data
public class RefundRequest {

    @NotBlank(message = "La razón del reembolso es obligatoria")
    private String reason;
}
