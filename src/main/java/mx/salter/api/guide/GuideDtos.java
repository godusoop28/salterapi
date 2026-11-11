package mx.salter.api.guide;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.common.AddressDto;

public final class GuideDtos {
    private GuideDtos() {}

    // ─────────── Entrada ───────────

    public record NewRecipientInput(
            @NotBlank(message = "El nombre del destinatario es obligatorio") @Size(max = 120) String firstName,
            @Size(max = 160) String lastName,
            @Size(max = 30) String phone,
            @Email @Size(max = 160) String email,
            @Valid AddressDto address,
            /** true: se guarda en el catálogo para futuros envíos; false: sólo en esta guía. */
            boolean saveToCatalog,
            boolean allowProbableDuplicate) {}

    public record ItemInput(
            Long conceptId,
            @Size(max = 300) String description,
            @NotNull @DecimalMin(value = "0.01", message = "La cantidad debe ser mayor a cero") BigDecimal quantity,
            @Size(max = 30) String unit,
            @DecimalMin(value = "0.0", message = "El peso no puede ser negativo") BigDecimal weightKg) {}

    /** Datos de traslado que se plasman en la guía: PBA → OAX, dimensiones, servicio y fecha compromiso. */
    public record ShippingInput(
            @Pattern(regexp = "^([A-Za-z]{2,5})?$", message = "Origen: 2 a 5 letras (ej. PBA)") String originCode,
            @Pattern(regexp = "^([A-Za-z]{2,5})?$", message = "Destino: 2 a 5 letras (ej. OAX)") String destinationCode,
            @Size(max = 60) String dimensions,
            @Size(max = 30) String serviceType,
            /** Si se omite se calcula con los días del tipo de servicio (configuración guide.services). */
            LocalDate commitmentDate) {}

    public record GuideRequest(
            /** Identificador generado por el cliente para evitar guías duplicadas por doble envío. */
            UUID clientRequestId,
            Long senderId,
            Long shiftId,
            Long recipientId,
            @Valid NewRecipientInput newRecipient,
            @Size(max = 80) String cdReference,
            @Size(max = 80) String orderReference,
            @Min(1) @Max(9999) Integer packageCount,
            @Valid ShippingInput shipping,
            @NotEmpty(message = "Agrega al menos un concepto de mercancía") @Size(max = 50) List<@Valid ItemInput> items,
            @Size(max = 1000) String notes,
            /** Confirmar inmediatamente (requiere permiso GUIDES_CONFIRM). */
            boolean confirm,
            Long version) {}

    public record ReasonRequest(@NotBlank(message = "Indica el motivo") @Size(max = 500) String reason) {}

    public record NoteRequest(@NotBlank @Size(max = 1000) String notes, @Size(max = 160) String location) {}

    public record CorrectionRequest(
            @NotBlank(message = "Indica el motivo de la corrección") @Size(max = 500) String reason,
            @Valid NewRecipientInput recipient,
            @Size(max = 80) String cdReference,
            @Size(max = 80) String orderReference,
            @Min(1) @Max(9999) Integer packageCount,
            @Valid ShippingInput shipping,
            @Size(max = 50) List<@Valid ItemInput> items,
            @Size(max = 1000) String notes,
            @NotNull Long version) {}

    public record GuideFilter(String q, List<GuideStatus> status, Long senderId, Long shiftId, Long courierId,
                              Boolean mine, LocalDate from, LocalDate to, Boolean withoutManifest, String folio) {}

    // ─────────── Salida ───────────

    public record PartyDto(String name, String contact, String phone, String email, AddressDto address) {}

    public record ItemDto(Long id, int position, Long conceptId, String description, BigDecimal quantity, String unit, BigDecimal weightKg) {}

    public record MovementDto(Long id, MovementType type, String typeLabel, GuideStatus fromStatus, GuideStatus toStatus,
                              String toStatusLabel, String userName, Instant occurredAt, String location, String notes,
                              Map<String, Object> metadata) {
        public static MovementDto of(GuideMovement m) {
            return new MovementDto(m.getId(), m.getType(), m.getType().getLabel(), m.getFromStatus(), m.getToStatus(),
                    m.getToStatus().getLabel(), m.getUserName(), m.getOccurredAt(), m.getLocation(), m.getNotes(), m.getMetadata());
        }
    }

    public record ShippingDto(String originCode, String destinationCode, String dimensions, String serviceType,
                              LocalDate commitmentDate) {}

    public record ManifestRef(Long id, String number, String status) {}

    public record AssignmentDto(Long courierId, String courierName, String courierPhone, Instant assignedAt, Integer routeOrder) {}

    public record ReceptionDto(Instant receivedAt, Long receivedBy, String location, Integer receivedCount,
                               boolean hasDifferences, String differences, String notes) {}

    public record DeliveryDto(Instant deliveredAt, Long deliveredBy, String receivedByName, String notes) {}

    public record GuideSummaryDto(Long id, String folio, String reference, GuideStatus status, String statusLabel,
                                  Long senderId, String senderName, Long recipientId, String recipientName, String recipientPhone,
                                  String recipientCity, String cdReference, String orderReference, int packageCount,
                                  String originCode, String destinationCode, Ref shift, Ref createdBy, Instant createdAt, Instant confirmedAt, long version) {}

    public record GuideDto(Long id, String folio, String reference, GuideStatus status, String statusLabel,
                           Long senderId, PartyDto sender, Long recipientId, boolean recipientOneTime, PartyDto recipient,
                           Ref shift, String cdReference, String orderReference, int packageCount, BigDecimal totalWeightKg,
                           ShippingDto shipping, String notes, List<ItemDto> items, Ref createdBy, Instant createdAt, Instant updatedAt,
                           Instant confirmedAt, Ref confirmedBy, Instant cancelledAt, Ref cancelledBy, String cancelReason,
                           ManifestRef manifest, AssignmentDto assignment, ReceptionDto reception, DeliveryDto delivery,
                           Long openIncidentId, List<MovementDto> movements, List<String> allowedActions, long version) {}

    public record CreateResult(GuideDto guide, boolean created) {}
}
