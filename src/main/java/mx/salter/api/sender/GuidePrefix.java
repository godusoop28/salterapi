package mx.salter.api.sender;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Siglas del folio de guía. Formato del cliente: "ATM 000000001", donde ATM son las iniciales del
 * remitente (Auto Todo Mexicana). Si el remitente tiene siglas configuradas se usan; si no, se calculan
 * con las iniciales de su nombre (sin artículos ni razón social).
 */
public final class GuidePrefix {
    private GuidePrefix() {}

    public static final String FALLBACK = "SAL";

    private static final Set<String> SKIP = Set.of(
            "DE", "DEL", "LA", "LAS", "EL", "LOS", "Y", "E", "EN", "A",
            "SA", "CV", "SC", "RL", "SAB", "SAPI", "S", "C", "V", "DECV", "SRL", "AC", "THE", "AND", "OF");

    public static String of(Sender s) {
        if (s == null) return FALLBACK;
        if (s.getGuidePrefix() != null && !s.getGuidePrefix().isBlank()) return s.getGuidePrefix().trim().toUpperCase(Locale.ROOT);
        return fromName(s.getName());
    }

    public static String fromName(String name) {
        if (name == null) return FALLBACK;
        // Quita lo que va entre paréntesis y la razón social (S.A. de C.V., etc.)
        String n = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT).replaceAll("\\(.*?\\)", " ").replace(".", "").replaceAll("[^A-Z0-9 ]", " ");
        StringBuilder sb = new StringBuilder();
        String first = null;
        for (String w : n.trim().split("\\s+")) {
            if (w.isEmpty() || SKIP.contains(w) || !Character.isLetter(w.charAt(0))) continue;
            if (first == null) first = w;
            sb.append(w.charAt(0));
            if (sb.length() == 4) break;
        }
        if (sb.length() == 1) {
            // Una sola palabra: primeras tres letras
            String w = first.replaceAll("[^A-Z]", "");
            return w.length() >= 3 ? w.substring(0, 3) : FALLBACK;
        }
        return sb.length() >= 2 ? sb.toString() : FALLBACK;
    }
}
