package mx.salter.api.common;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressDto(
        @Size(max = 200) String street,
        @Size(max = 120) String neighborhood,
        @Size(max = 120) String city,
        @Size(max = 120) String state,
        @Pattern(regexp = "^$|^\\d{5}$", message = "El código postal debe tener 5 dígitos") String zip,
        @Size(max = 300) String references) {

    public static AddressDto of(Address a) {
        if (a == null) return new AddressDto(null, null, null, null, null, null);
        return new AddressDto(a.getStreet(), a.getNeighborhood(), a.getCity(), a.getState(), a.getZip(), a.getReferences());
    }

    public Address toEntity() {
        return new Address(Text.trimToNull(street), Text.trimToNull(neighborhood), Text.trimToNull(city),
                Text.trimToNull(state), Text.trimToNull(zip), Text.trimToNull(references));
    }
}
