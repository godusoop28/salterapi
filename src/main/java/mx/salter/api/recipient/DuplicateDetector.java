package mx.salter.api.recipient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import mx.salter.api.common.Text;

/**
 * Reglas de duplicados (no dependen sólo del nombre):
 * <ul>
 *   <li>EXACTO: mismo nombre normalizado y (mismo teléfono o misma calle + código postal).</li>
 *   <li>PROBABLE: mismo teléfono con otro nombre, o mismo nombre sin teléfono/dirección coincidentes.</li>
 * </ul>
 * Las reglas son PROVISIONALES y se documentan en PENDIENTES_CLIENTE.md.
 */
public final class DuplicateDetector {
    private DuplicateDetector() {}

    public enum Level { NONE, PROBABLE, EXACT }

    public record Candidate(String nameKey, String phoneDigits, String streetKey, String zip) {
        public static Candidate of(String first, String last, String phone, String street, String zip) {
            return new Candidate(Recipient.nameKey(first, last), Text.digits(phone), Text.normalize(street), Text.trimToNull(zip));
        }

        public static Candidate of(Recipient r) {
            return new Candidate(r.getNameKey(), r.getPhoneDigits(),
                    Text.normalize(r.getAddress() == null ? null : r.getAddress().getStreet()),
                    r.getAddress() == null ? null : r.getAddress().getZip());
        }
    }

    public record Match(Long recipientId, String name, Level level, String reason) {}

    public static Level compare(Candidate a, Candidate b) {
        boolean sameName = !a.nameKey().isEmpty() && a.nameKey().equals(b.nameKey());
        boolean samePhone = a.phoneDigits() != null && a.phoneDigits().equals(b.phoneDigits());
        boolean sameAddress = !a.streetKey().isEmpty() && a.streetKey().equals(b.streetKey())
                && Objects.equals(a.zip(), b.zip());
        if (sameName && (samePhone || sameAddress)) return Level.EXACT;
        if (samePhone || sameName) return Level.PROBABLE;
        return Level.NONE;
    }

    public static String reason(Candidate a, Candidate b) {
        List<String> r = new ArrayList<>();
        if (!a.nameKey().isEmpty() && a.nameKey().equals(b.nameKey())) r.add("mismo nombre");
        if (a.phoneDigits() != null && a.phoneDigits().equals(b.phoneDigits())) r.add("mismo teléfono");
        if (!a.streetKey().isEmpty() && a.streetKey().equals(b.streetKey()) && Objects.equals(a.zip(), b.zip())) r.add("misma dirección");
        return String.join(", ", r);
    }

    public static List<Match> matches(Candidate c, List<Recipient> existing) {
        List<Match> out = new ArrayList<>();
        for (Recipient r : existing) {
            Candidate other = Candidate.of(r);
            Level l = compare(c, other);
            if (l != Level.NONE) out.add(new Match(r.getId(), r.fullName(), l, reason(c, other)));
        }
        out.sort((x, y) -> y.level().compareTo(x.level()));
        return out;
    }
}
