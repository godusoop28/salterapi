package mx.salter.api.recipient;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import mx.salter.api.audit.AuditService;
import mx.salter.api.common.Address;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Text;
import mx.salter.api.recipient.DuplicateDetector.Candidate;
import mx.salter.api.recipient.DuplicateDetector.Level;
import mx.salter.api.recipient.DuplicateDetector.Match;
import mx.salter.api.recipient.RecipientDtos.ImportPreview;
import mx.salter.api.recipient.RecipientDtos.ImportRequest;
import mx.salter.api.recipient.RecipientDtos.ImportResult;
import mx.salter.api.recipient.RecipientDtos.ImportRow;
import mx.salter.api.recipient.RecipientDtos.RowResult;
import mx.salter.api.recipient.RecipientDtos.RowStatus;
import mx.salter.api.recipient.RecipientDtos.ValidationResult;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importación masiva: 1) vista previa del archivo, 2) la interfaz relaciona columnas con campos,
 * 3) validación por fila con detección de duplicados, 4) confirmación en una sola transacción.
 */
@Service
public class RecipientImportService {
    public static final List<String> TARGET_FIELDS = List.of("clientCode", "firstName", "lastName", "phone", "email", "street",
            "extNumber", "neighborhood", "city", "state", "zip", "route", "subRoute", "references", "notes");

    private static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();
    static {
        SYNONYMS.put("clientCode", List.of("# cliente", "cliente", "no cliente", "num cliente", "numero de cliente", "clave cliente", "codigo cliente", "id cliente"));
        SYNONYMS.put("subRoute", List.of("sub ruta", "subruta", "clave cd", "cd"));
        SYNONYMS.put("route", List.of("ruta"));
        SYNONYMS.put("extNumber", List.of("#", "no", "no.", "num", "num ext", "numero exterior", "no ext", "exterior"));
        SYNONYMS.put("firstName", List.of("nombre", "nombres", "name", "first name", "nombre completo", "destinatario"));
        SYNONYMS.put("lastName", List.of("apellido", "apellidos", "last name", "apellido paterno", "primer apellido"));
        SYNONYMS.put("phone", List.of("telefono", "tel", "celular", "movil", "phone", "whatsapp", "numero"));
        SYNONYMS.put("email", List.of("correo", "email", "e-mail", "correo electronico", "mail"));
        SYNONYMS.put("street", List.of("calle", "direccion", "domicilio", "calle y numero", "address", "street"));
        SYNONYMS.put("neighborhood", List.of("colonia", "col", "barrio", "fraccionamiento"));
        SYNONYMS.put("city", List.of("ciudad", "municipio", "localidad", "city", "alcaldia", "destino"));
        SYNONYMS.put("state", List.of("estado", "entidad", "state"));
        SYNONYMS.put("zip", List.of("cp", "c.p.", "c p", "codigo postal", "zip", "postal"));
        SYNONYMS.put("references", List.of("referencias", "referencia", "entre calles"));
        SYNONYMS.put("notes", List.of("notas", "observaciones", "comentarios"));
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");

    private final RecipientRepository repo;
    private final SenderService senders;
    private final AuditService audit;
    private final EntityManager em;
    private final int maxRows;

    public RecipientImportService(RecipientRepository repo, SenderService senders, AuditService audit, EntityManager em,
                                  @Value("${salter.imports.max-rows:5000}") int maxRows) {
        this.repo = repo;
        this.senders = senders;
        this.audit = audit;
        this.em = em;
        this.maxRows = maxRows;
    }

    public ImportPreview preview(MultipartFile file) {
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_FILE", "No se pudo leer el archivo.");
        }
        SpreadsheetReader.Table t = SpreadsheetReader.read(file.getOriginalFilename(), data, maxRows);
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> r : t.rows()) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < t.headers().size(); i++) m.put(t.headers().get(i), i < r.size() ? r.get(i) : "");
            rows.add(m);
        }
        return new ImportPreview(file.getOriginalFilename(), t.headers(), rows, rows.size(), suggest(t.headers()), TARGET_FIELDS);
    }

    static Map<String, String> suggest(List<String> headers) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (var e : SYNONYMS.entrySet()) {
            for (String h : headers) {
                if (used.contains(h)) continue;
                String n = headerKey(h);
                String compact = n.replace(" ", "");
                if (e.getValue().stream().anyMatch(syn -> n.equals(syn) || n.startsWith(syn + " ")
                        || (syn.length() > 2 && compact.equals(syn.replace(" ", ""))))) {
                    mapping.put(e.getKey(), h);
                    used.add(h);
                    break;
                }
            }
        }
        return mapping;
    }

    /** "             N  O  M  B  R  E" → "nombre"; "# CLIENTE" → "# cliente"; "#" → "#". */
    static String headerKey(String h) {
        String raw = h == null ? "" : h.trim();
        if (raw.equals("#")) return "#";
        boolean hash = raw.startsWith("#");
        String n = Text.normalize(raw);
        // Letras separadas por espacios (encabezados "espaciados"): se unen.
        if (n.matches("^(\\S\\s+){2,}\\S$")) n = n.replace(" ", "");
        else n = n.replaceAll("(?<=\\b\\S)\\s(?=\\S\\b)", "");
        return hash ? ("# " + n).trim() : n;
    }

    @Transactional(readOnly = true)
    public ValidationResult validate(ImportRequest req) {
        CurrentUser.require(mx.salter.api.security.Permission.RECIPIENTS_IMPORT);
        Sender s = senders.requireAccessible(req.senderId());
        return evaluate(s.getId(), req.rows());
    }

    @Transactional
    public ImportResult commit(ImportRequest req) {
        CurrentUser.require(mx.salter.api.security.Permission.RECIPIENTS_IMPORT);
        Sender s = senders.requireAccessible(req.senderId());
        // Serializa importaciones simultáneas del mismo remitente.
        em.createNativeQuery("select pg_advisory_xact_lock(:k)").setParameter("k", 7_000_000_000L + s.getId()).getSingleResult();
        ValidationResult v = evaluate(s.getId(), req.rows());
        Map<Integer, ImportRow> byNumber = new HashMap<>();
        req.rows().forEach(r -> byNumber.put(r.rowNumber(), r));
        Long userId = CurrentUser.get().userId();
        List<Recipient> toSave = new ArrayList<>();
        int dup = 0, err = 0, prob = 0;
        for (RowResult rr : v.rows()) {
            boolean include = rr.status() == RowStatus.VALID
                    || (rr.status() == RowStatus.PROBABLE_DUPLICATE && req.importProbableDuplicates());
            if (!include) {
                switch (rr.status()) {
                    case DUPLICATE -> dup++;
                    case ERROR -> err++;
                    case PROBABLE_DUPLICATE -> prob++;
                    default -> { }
                }
                continue;
            }
            ImportRow row = byNumber.get(rr.rowNumber());
            Recipient r = new Recipient();
            r.setSender(s);
            r.setFirstName(Text.truncate(row.firstName().trim().replaceAll("\\s+", " "), 120));
            r.setLastName(Text.trimToNull(row.lastName()));
            r.setPhone(Text.trimToNull(row.phone()));
            r.setEmail(Text.trimToNull(row.email()));
            r.setClientCode(cleanCode(row.clientCode()));
            r.setRoute(cleanCode(row.route()));
            r.setSubRoute(RecipientDtos.normalizeSubRoute(row.subRoute()));
            r.setAddress(new Address(streetWithNumber(row), Text.trimToNull(row.neighborhood()), Text.trimToNull(row.city()),
                    Text.trimToNull(row.state()), cleanZip(row.zip()), Text.trimToNull(row.references())));
            r.setNotes(Text.trimToNull(row.notes()));
            r.setCreatedBy(userId);
            toSave.add(r);
        }
        repo.saveAll(toSave);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("senderId", s.getId());
        d.put("rows", req.rows().size());
        d.put("created", toSave.size());
        d.put("skippedDuplicates", dup);
        d.put("skippedErrors", err);
        d.put("skippedProbable", prob);
        audit.record("RECIPIENTS_IMPORTED", "SENDER", s.getId(), d);
        return new ImportResult(toSave.size(), dup, err, prob, v.rows());
    }

    private ValidationResult evaluate(Long senderId, List<ImportRow> rows) {
        if (rows.size() > maxRows) throw ApiException.badRequest("TOO_MANY_ROWS", "Máximo " + maxRows + " filas por importación.");
        Set<String> nameKeys = new HashSet<>();
        Set<String> phones = new HashSet<>();
        for (ImportRow r : rows) {
            nameKeys.add(Recipient.nameKey(r.firstName(), r.lastName()));
            String d = Text.digits(r.phone());
            if (d != null) phones.add(d);
        }
        nameKeys.add("~");
        phones.add("~");
        List<Recipient> existing = repo.findCandidatesBulk(senderId, nameKeys, phones);

        List<RowResult> results = new ArrayList<>();
        List<Candidate> accepted = new ArrayList<>();
        List<Integer> acceptedRows = new ArrayList<>();
        int valid = 0, errors = 0, dups = 0, probable = 0;
        for (ImportRow r : rows) {
            List<String> errs = rowErrors(r);
            if (!errs.isEmpty()) {
                results.add(new RowResult(r.rowNumber(), RowStatus.ERROR, errs, List.of()));
                errors++;
                continue;
            }
            Candidate c = Candidate.of(r.firstName(), r.lastName(), r.phone(), streetWithNumber(r), cleanZip(r.zip()));
            List<Match> matches = new ArrayList<>(DuplicateDetector.matches(c, existing));
            for (int i = 0; i < accepted.size(); i++) {
                Level l = DuplicateDetector.compare(c, accepted.get(i));
                if (l != Level.NONE) {
                    matches.add(new Match(null, "Fila " + acceptedRows.get(i) + " del archivo", l,
                            DuplicateDetector.reason(c, accepted.get(i))));
                }
            }
            RowStatus st;
            if (matches.stream().anyMatch(m -> m.level() == Level.EXACT)) {
                st = RowStatus.DUPLICATE;
                dups++;
            } else if (!matches.isEmpty()) {
                st = RowStatus.PROBABLE_DUPLICATE;
                probable++;
                accepted.add(c);
                acceptedRows.add(r.rowNumber());
            } else {
                st = RowStatus.VALID;
                valid++;
                accepted.add(c);
                acceptedRows.add(r.rowNumber());
            }
            results.add(new RowResult(r.rowNumber(), st, List.of(), matches));
        }
        return new ValidationResult(rows.size(), valid, errors, dups, probable, results);
    }

    private static List<String> rowErrors(ImportRow r) {
        List<String> e = new ArrayList<>();
        if (r.firstName() == null || r.firstName().isBlank()) e.add("El nombre es obligatorio.");
        if (len(r.firstName()) > 160) e.add("El nombre excede 160 caracteres.");
        if (len(r.clientCode()) > 30) e.add("El número de cliente excede 30 caracteres.");
        if (len(r.subRoute()) > 20 || len(r.route()) > 20) e.add("La ruta o subruta es demasiado larga.");
        if (len(r.lastName()) > 160) e.add("El apellido excede 160 caracteres.");
        if (len(r.street()) > 200) e.add("La calle excede 200 caracteres.");
        if (len(r.neighborhood()) > 120 || len(r.city()) > 120 || len(r.state()) > 120) e.add("Colonia, ciudad o estado demasiado largos.");
        if (len(r.references()) > 300) e.add("Las referencias exceden 300 caracteres.");
        if (len(r.notes()) > 500) e.add("Las notas exceden 500 caracteres.");
        String zip = cleanCode(r.zip());
        if (zip != null && zip.matches("^\\d{4}$")) zip = "0" + zip;
        if (zip != null && !ZIP.matcher(zip).matches()) e.add("El código postal debe tener 5 dígitos.");
        String email = Text.trimToNull(r.email());
        if (email != null && (!EMAIL.matcher(email).matches() || email.length() > 160)) e.add("El correo no es válido.");
        String phone = Text.trimToNull(r.phone());
        if (phone != null) {
            String d = phone.replaceAll("\\D", "");
            if (d.length() < 7 || d.length() > 15 || phone.length() > 30) e.add("El teléfono no es válido.");
        }
        return e;
    }

    static String cleanZip(String v) {
        String z = cleanCode(v);
        return z != null && z.matches("^\\d{4}$") ? "0" + z : z;
    }

    /** "11081.0" → "11081" (números leídos de Excel). */
    static String cleanCode(String v) {
        String t = Text.trimToNull(v);
        if (t == null) return null;
        return t.matches("^\\d+\\.0+$") ? t.substring(0, t.indexOf('.')) : t;
    }

    /** Calle + número exterior (columna "#") sin duplicar el número si ya viene en la calle. */
    static String streetWithNumber(ImportRow r) {
        String street = Text.trimToNull(r.street());
        String num = cleanCode(r.extNumber());
        if (street != null) street = street.replaceAll("\\s+", " ");
        if (num == null) return street;
        if (street == null) return num;
        if (street.matches(".*\\b" + java.util.regex.Pattern.quote(num) + "\\b.*")) return street;
        return Text.truncate(street + " " + num, 200);
    }

    private static int len(String s) {
        return s == null ? 0 : s.trim().length();
    }
}
