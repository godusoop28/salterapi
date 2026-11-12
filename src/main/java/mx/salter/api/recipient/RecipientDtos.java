package mx.salter.api.recipient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mx.salter.api.common.AddressDto;
import mx.salter.api.recipient.DuplicateDetector.Match;

public final class RecipientDtos {
    private RecipientDtos() {}

    public record RecipientDto(Long id, Long senderId, String senderName, String firstName, String lastName, String fullName,
                               String phone, String email, AddressDto address, String notes, String clientCode, String route,
                               String subRoute, String destinationCode, boolean active, Instant createdAt, long version) {
        public static RecipientDto of(Recipient r) {
            return new RecipientDto(r.getId(), r.getSender().getId(), r.getSender().getName(), r.getFirstName(), r.getLastName(),
                    r.fullName(), r.getPhone(), r.getEmail(), AddressDto.of(r.getAddress()), r.getNotes(), r.getClientCode(), r.getRoute(),
                    r.getSubRoute(), plazaOf(r.getSubRoute()), r.isActive(),
                    r.getCreatedAt(), r.getVersion());
        }
    }

    /** Plaza destino a partir de la subruta: "CVA-01" → "CVA". */
    public static String plazaOf(String subRoute) {
        if (subRoute == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z]{2,5})").matcher(subRoute.trim());
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** Normaliza la subruta: "CVA – 01", "cva 01" → "CVA-01". */
    public static String normalizeSubRoute(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase().replaceAll("[\\s\\u2010-\\u2015_-]+", "-").replaceAll("-+", "-");
        return s.replaceAll("^-|-$", "");
    }

    public record RecipientRequest(
            Long senderId,
            @NotBlank(message = "El nombre es obligatorio") @Size(max = 120) String firstName,
            @Size(max = 160) String lastName,
            @Size(max = 30) String phone,
            @Email @Size(max = 160) String email,
            @Valid AddressDto address,
            @Size(max = 500) String notes,
            @Size(max = 30) String clientCode,
            @Size(max = 20) String route,
            @Size(max = 20) String subRoute,
            /** Permite guardar aunque existan coincidencias probables (las exactas siempre se bloquean). */
            boolean allowProbableDuplicate,
            Long version) {}

    public record DuplicateCheckRequest(Long senderId, @NotBlank String firstName, String lastName, String phone,
                                        String street, String zip, Long excludeId) {}

    public record DuplicateCheckResponse(boolean exactDuplicate, List<Match> matches) {}

    // ─────────── Importación ───────────

    public record ImportPreview(String fileName, List<String> headers, List<Map<String, String>> rows, int totalRows,
                                Map<String, String> suggestedMapping, List<String> targetFields) {}

    public record ImportRow(
            int rowNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            String street,
            String neighborhood,
            String city,
            String state,
            String zip,
            String references,
            String notes,
            String clientCode,
            String extNumber,
            String route,
            String subRoute) {}

    public record ImportRequest(Long senderId, @NotNull @NotEmpty @Size(max = 5000) List<ImportRow> rows,
                                boolean importProbableDuplicates) {}

    public enum RowStatus { VALID, ERROR, DUPLICATE, PROBABLE_DUPLICATE }

    public record RowResult(int rowNumber, RowStatus status, List<String> errors, List<Match> matches) {}

    public record ValidationResult(int total, int valid, int errors, int duplicates, int probableDuplicates,
                                   List<RowResult> rows) {}

    public record ImportResult(int created, int skippedDuplicates, int skippedErrors, int skippedProbable,
                               List<RowResult> rows) {}
}
