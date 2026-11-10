package mx.salter.api.manifest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;

public final class ManifestDtos {
    private ManifestDtos() {}

    public record ManifestRequest(
            @NotNull(message = "Indica la fecha de operación") LocalDate operationDate,
            Long shiftId,
            Long senderId,
            @Size(max = 1000) String notes,
            @NotNull @Size(max = 1000) List<Long> guideIds,
            Long version) {}

    public record DispatchRequest(Long courierId, @Size(max = 80) String vehicle, @Size(max = 1000) String notes,
                                  @NotNull Long version) {}

    public record ReopenRequest(@NotNull @Size(min = 3, max = 500) String reason, @NotNull Long version) {}

    public record DispatchDto(Instant dispatchedAt, Ref dispatchedBy, Long courierId, String courierName, String vehicle,
                              String notes, int guideCount) {}

    public record ManifestSummaryDto(Long id, String number, Manifest.Status status, LocalDate operationDate, Ref shift,
                                     Ref sender, int guideCount, Ref createdBy, Instant createdAt, Instant closedAt, long version) {}

    public record ManifestDto(Long id, String number, Manifest.Status status, LocalDate operationDate, Ref shift, Ref sender,
                              String notes, Ref createdBy, Instant createdAt, Instant closedAt, Ref closedBy,
                              int guideCount, int packageCount, List<GuideSummaryDto> guides, DispatchDto dispatch,
                              List<String> allowedActions, long version) {}
}
