package mx.salter.api.sender;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import mx.salter.api.common.AddressDto;

public final class SenderDtos {
    private SenderDtos() {}

    public record SenderDto(Long id, String code, String name, Sender.Kind kind, String contactName, String phone,
                            String email, AddressDto address, String notes, String plazaCode, String guidePrefix,
                            String effectiveGuidePrefix, boolean active, Instant createdAt, long version) {
        public static SenderDto of(Sender s) {
            return new SenderDto(s.getId(), s.getCode(), s.getName(), s.getKind(), s.getContactName(), s.getPhone(),
                    s.getEmail(), AddressDto.of(s.getAddress()), s.getNotes(), s.getPlazaCode(), s.getGuidePrefix(),
                    GuidePrefix.of(s), s.isActive(), s.getCreatedAt(), s.getVersion());
        }
    }

    public record SenderRequest(
            @Size(max = 30) String code,
            @NotBlank @Size(max = 160) String name,
            @NotNull Sender.Kind kind,
            @Size(max = 160) String contactName,
            @Size(max = 30) String phone,
            @Email @Size(max = 160) String email,
            @Valid AddressDto address,
            @Size(max = 500) String notes,
            @Pattern(regexp = "^([A-Za-z]{2,5})?$", message = "Plaza: 2 a 5 letras (ej. PBA)") String plazaCode,
            @Pattern(regexp = "^([A-Za-z]{2,6})?$", message = "Siglas del folio: 2 a 6 letras (ej. ATM)") String guidePrefix,
            Boolean active,
            Long version) {}
}
