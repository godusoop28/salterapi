package mx.salter.api.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.FolioSequenceRepository;
import mx.salter.api.catalog.SettingRepository;
import mx.salter.api.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    /** Claves visibles sin iniciar sesión (rastreo público, encabezados). */
    private static final Set<String> PUBLIC_KEYS = Set.of("company.name", "company.slogan", "company.phone", "company.email", "tracking.public");

    private final SettingRepository repo;
    private final FolioSequenceRepository folios;
    private final AuditService audit;

    public SettingsService(SettingRepository repo, FolioSequenceRepository folios, AuditService audit) {
        this.repo = repo;
        this.folios = folios;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public String get(String key, String fallback) {
        return repo.findById(key).map(AppSetting::getValue).filter(v -> v != null && !v.isBlank()).orElse(fallback);
    }

    @Transactional(readOnly = true)
    public boolean getBool(String key, boolean fallback) {
        return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> out = new TreeMap<>();
        repo.findAll().forEach(s -> out.put(s.getKey(), s.getValue()));
        folios.findAll().forEach(f -> {
            out.put("folio." + f.getKey().toLowerCase() + ".prefix", f.getPrefix());
            out.put("folio." + f.getKey().toLowerCase() + ".padding", String.valueOf(f.getPadding()));
        });
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, String> publicSettings() {
        Map<String, String> out = new TreeMap<>();
        repo.findAllById(PUBLIC_KEYS).forEach(s -> out.put(s.getKey(), s.getValue()));
        return out;
    }

    @Transactional
    public Map<String, String> update(Map<String, String> values) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.length() > 2000) throw ApiException.badRequest("INVALID_SETTING", "Valor demasiado largo: " + key);
            if (key.startsWith("folio.")) {
                applyFolio(key, value);
            } else {
                AppSetting s = repo.findById(key).orElseThrow(() -> ApiException.badRequest("INVALID_SETTING", "Configuración desconocida: " + key));
                s.setValue(value);
            }
            changes.put(key, value);
        }
        audit.record("SETTINGS_UPDATED", "SETTINGS", null, changes);
        return all();
    }

    private void applyFolio(String key, String value) {
        String[] parts = key.split("\\.");
        if (parts.length != 3) throw ApiException.badRequest("INVALID_SETTING", "Configuración desconocida: " + key);
        FolioSequence f = folios.lockByKey(parts[1].toUpperCase())
                .orElseThrow(() -> ApiException.badRequest("INVALID_SETTING", "Configuración desconocida: " + key));
        switch (parts[2]) {
            case "prefix" -> {
                if (!value.matches("[A-Z0-9]{1,10}")) throw ApiException.badRequest("INVALID_SETTING", "El prefijo debe tener de 1 a 10 letras mayúsculas o números.");
                f.setPrefix(value);
            }
            case "padding" -> {
                int p;
                try {
                    p = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw ApiException.badRequest("INVALID_SETTING", "Longitud de folio no válida.");
                }
                if (p < 3 || p > 12) throw ApiException.badRequest("INVALID_SETTING", "La longitud del número debe estar entre 3 y 12.");
                f.setPadding(p);
            }
            default -> throw ApiException.badRequest("INVALID_SETTING", "Configuración desconocida: " + key);
        }
    }
}
