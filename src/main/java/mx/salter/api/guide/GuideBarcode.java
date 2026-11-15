package mx.salter.api.guide;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Contenido del código PDF417 de la guía, VERSIONADO.
 *
 * <pre>
 *  V2:     FOLIO|V2|ORIGEN|DESTINO|PIEZAS|PESO|FECHA|SUBRUTA|SERVICIO|COMPROMISO
 *          ATM000000001|V2|PBA|OAX|8|NA|20261113|CVA-01|ORDINARIO|20261114
 *  Legado: FOLIO|ORIGEN|DESTINO|PIEZAS|PESO|FECHA|CLAVE
 * </pre>
 *
 * El folio va SIEMPRE primero para que cualquier lector (y {@link GuideService#normalizeFolio}) lo obtenga directo.
 * El código lleva únicamente los datos operativos esenciales y NINGÚN dato personal del destinatario: la información
 * completa autorizada se consulta con el folio en el endpoint autenticado {@code GET /api/guides/by-folio/{codigo}}.
 * PESO: kilos, {@code NA} = no aplica, vacío = no capturado.
 */
public final class GuideBarcode {
    private GuideBarcode() {}

    public static final String VERSION = "V2";
    public static final String NOT_APPLICABLE = "NA";

    public record Payload(String version, String folio, String originCode, String destinationCode, Integer pieces,
                          String weight, LocalDate date, String subRoute, String serviceType, LocalDate commitmentDate) {}

    public static String encode(Guide g, ZoneId zone) {
        String date = g.getConfirmedAt() == null ? "" : DateTimeFormatter.BASIC_ISO_DATE.format(g.getConfirmedAt().atZone(zone).toLocalDate());
        String weight = g.isWeightNotApplicable() ? NOT_APPLICABLE
                : g.getTotalWeightKg() == null ? "" : g.getTotalWeightKg().stripTrailingZeros().toPlainString();
        String commitment = g.getCommitmentDate() == null ? "" : DateTimeFormatter.BASIC_ISO_DATE.format(g.getCommitmentDate());
        return String.join("|", clean(g.getFolio()), VERSION, clean(g.getOriginCode()), clean(g.getDestinationCode()),
                g.getPackageCount() == null ? "" : String.valueOf(g.getPackageCount()), weight, date, clean(g.getSubRoute()),
                clean(g.getServiceType()), commitment);
    }

    /** Interpreta un código leído por escáner (V2 o formato anterior). */
    public static Payload decode(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Código vacío");
        String[] p = Arrays.copyOf(raw.trim().split("\\|", -1), 10);
        String folio = GuideService.normalizeFolio(p[0]);
        if (VERSION.equals(p[1])) {
            return new Payload(VERSION, folio, blank(p[2]), blank(p[3]), intOrNull(p[4]), blank(p[5]), date(p[6]), blank(p[7]),
                    blank(p[8]), date(p[9]));
        }
        return new Payload("V1", folio, blank(p[1]), blank(p[2]), intOrNull(p[3]), blank(p[4]), date(p[5]), blank(p[6]), null, null);
    }

    private static String clean(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace("|", "/").trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        try {
            if (s == null || s.isBlank()) return null;
            String d = s.trim();
            // Códigos impresos antes de V2 podían traer la zona: "20261113-0600".
            if (d.length() > 8) d = d.substring(0, 8);
            return LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
