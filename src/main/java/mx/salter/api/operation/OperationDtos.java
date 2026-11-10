package mx.salter.api.operation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import mx.salter.api.guide.GuideDtos.GuideSummaryDto;

public final class OperationDtos {
    private OperationDtos() {}

    public record CourierDto(Long id, String name, String phone, String email, String zone, String vehicle, String plate,
                             Courier.Status status, boolean active, long activeAssignments, Long userId, long version) {}

    public record CourierRequest(@NotBlank @Size(max = 160) String name, @Size(max = 30) String phone,
                                 @Email @Size(max = 160) String email, @Size(max = 120) String zone,
                                 @Size(max = 80) String vehicle, @Size(max = 20) String plate,
                                 Courier.Status status, Boolean active, Long version) {}

    public record AssignRequest(@NotNull Long courierId, @NotEmpty @Size(max = 500) List<Long> guideIds) {}

    public record AssignResult(int assigned) {}

    public record ReceptionRequest(
            @Size(max = 160) String location,
            @Min(0) Integer receivedCount,
            boolean hasDifferences,
            @Size(max = 1000) String differences,
            @Size(max = 1000) String notes) {}

    public record DeliveryRequest(@NotBlank(message = "Indica quién recibe") @Size(max = 160) String receivedByName,
                                  @Size(max = 1000) String notes) {}

    public record CourierAssignmentDto(Long assignmentId, Integer routeOrder, Instant assignedAt, GuideSummaryDto guide,
                                       String street, String neighborhood, String references, String zip, String notes) {}

    public record HistoryItemDto(Instant assignedAt, boolean active, Instant releasedAt, GuideSummaryDto guide) {}
}
