package mx.salter.api.route;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import mx.salter.api.auth.AuthDtos.Ref;

public final class RouteDtos {
    private RouteDtos() {}

    public record RouteDto(Long id, String code, String name, boolean active, Ref responsible, String notes,
                           Instant createdAt, long version) {
        public static RouteDto of(ShippingRoute r) {
            return new RouteDto(r.getId(), r.getCode(), r.getName(), r.isActive(),
                    r.getResponsible() == null ? null : new Ref(r.getResponsible().getId(), r.getResponsible().getFullName()),
                    r.getNotes(), r.getCreatedAt(), r.getVersion());
        }
    }

    public record RouteRequest(
            @NotBlank(message = "Indica la clave de la ruta")
            @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$", message = "Clave: letras, números o guion (máx. 20)") String code,
            @NotBlank(message = "Indica el nombre de la ruta") @Size(max = 120) String name,
            Boolean active,
            Long responsibleUserId,
            @Size(max = 500) String notes,
            Long version) {}

    /** Cambio de ruta de embarque de una guía: siempre con motivo y confirmación explícita en la interfaz. */
    public record RouteChangeRequest(@NotNull(message = "Selecciona la nueva ruta") Long shippingRouteId,
                                     @NotBlank(message = "Indica el motivo del cambio de ruta") @Size(max = 500) String reason,
                                     Long version) {}

    /** Resumen operativo por ruta: manifiesto en preparación y guías vigentes por estado. */
    public record RouteOverview(RouteDto route, Ref openManifest, int openManifestGuides, Map<String, Long> guidesByStatus,
                                long pendingWithoutManifest) {}
}
