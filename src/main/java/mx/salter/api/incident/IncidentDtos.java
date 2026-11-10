package mx.salter.api.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import mx.salter.api.guide.GuideStatus;

public final class IncidentDtos {
    private IncidentDtos() {}

    public enum Outcome { RESTAURAR, RETENER, CANCELAR }

    public record CreateIncidentRequest(@NotNull Long guideId, @NotNull Incident.Type type, @NotNull Incident.Severity severity,
                                        @NotBlank @Size(max = 2000) String description) {}

    public record NoteRequest(@NotBlank @Size(max = 2000) String text) {}

    public record StatusRequest(@NotNull Incident.Status status, @Size(max = 2000) String resolution, Outcome outcome,
                                @NotNull Long version) {}

    public record NoteDto(Long id, String authorName, String text, Instant createdAt) {}

    public record IncidentDto(Long id, String folio, Long guideId, String guideFolio, GuideStatus guideStatus,
                              Incident.Type type, Incident.Severity severity, Incident.Status status, String description,
                              GuideStatus guideStatusBefore, String reportedByName, Instant createdAt, Instant resolvedAt,
                              String resolution, List<NoteDto> notes, long version) {}
}
