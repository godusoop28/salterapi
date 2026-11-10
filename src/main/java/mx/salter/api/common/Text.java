package mx.salter.api.common;

import java.text.Normalizer;
import java.util.Locale;

/** Normalización de texto: minúsculas, sin acentos, espacios simples. */
public final class Text {
    private Text() {}

    public static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@.\\- ]", " ").replaceAll("\\s+", " ").trim();
    }

    public static String digits(String value) {
        if (value == null) return null;
        String d = value.replaceAll("\\D", "");
        if (d.isEmpty()) return null;
        // Conserva los últimos 10 dígitos (número nacional en México) para comparar.
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    public static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    public static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    public static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Patrón LIKE normalizado con comodines escapados. */
    public static String likePattern(String term) {
        String n = normalize(term).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + n + "%";
    }
}
