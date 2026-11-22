package mx.salter.api.guide;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import mx.salter.api.common.AddressDto;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Text;
import mx.salter.api.recipient.Recipient;
import mx.salter.api.recipient.RecipientDtos;
import mx.salter.api.recipient.RecipientDtos.RecipientDto;
import mx.salter.api.recipient.RecipientRepository;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.SalterPrincipal;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Genera guías a partir del reporte de carga del cliente (ej. "74f.xlsx"): columnas «Nombre de cliente», «Pedido»,
 * «Dirección», «LPN», «Tipo LPN», «Carga» y «Ruta». Cada LPN es un bulto; las filas sin nombre continúan al cliente
 * anterior y la dirección puede venir partida en dos filas. Se agrupa por cliente: una guía por cliente con sus pedidos.
 */
@Service
public class GuideImportService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 20_000;
    private static final Pattern TOTAL = Pattern.compile("total de bultos\\D*(\\d+)");
    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");

    private final SenderService senders;
    private final RecipientRepository recipients;
    private final GuideRepository guides;
    private final GuideService guideService;
    private final mx.salter.api.recipient.RecipientService recipientService;
    private final GuideImportBatchRepository batches;
    private final GuidePackageRepository packageRepo;
    private final mx.salter.api.audit.AuditService audit;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public GuideImportService(SenderService senders, RecipientRepository recipients, GuideRepository guides, GuideService guideService,
                              mx.salter.api.recipient.RecipientService recipientService, GuideImportBatchRepository batches,
                              GuidePackageRepository packageRepo, mx.salter.api.audit.AuditService audit,
                              org.springframework.transaction.PlatformTransactionManager txManager) {
        this.senders = senders;
        this.recipients = recipients;
        this.guides = guides;
        this.guideService = guideService;
        this.recipientService = recipientService;
        this.batches = batches;
        this.packageRepo = packageRepo;
        this.audit = audit;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record TypeCount(String name, int quantity) {}

    /** Bulto del reporte: LPN, tipo y pedido (se conserva la relación original). */
    public record PackageLine(String lpn, String type, String order) {}

    public record ImportGroup(String requestId, String clientName, List<String> orders, String addressText, AddressDto address,
                              int packages, List<TypeCount> types, List<String> lpns, String route, String notes,
                              String matchType, RecipientDto recipient, List<RecipientDto> suggestions,
                              Long existingGuideId, String existingFolio, List<String> duplicateOrders,
                              List<PackageLine> packageLines, String suggestedSubRoute) {}

    public record ImportPreview(String fileName, Long senderId, String senderName, String load, String route,
                                Integer declaredPackages, int totalPackages, String defaultDestination, List<ImportGroup> groups,
                                /** Identificador del archivo (carga o huella): agrupa las guías en un lote y evita duplicados. */
                                String fileKey, Long defaultShippingRouteId) {}

    // ─────────── Confirmación masiva ───────────

    public record CommitItem(
            @jakarta.validation.constraints.NotBlank(message = "Indica el tipo de bulto") @jakarta.validation.constraints.Size(max = 60) String type,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(value = 1, message = "La cantidad debe ser al menos 1")
            @jakarta.validation.constraints.Max(9999) Integer quantity) {}

    public record CommitPackage(@jakarta.validation.constraints.Size(max = 60) String lpn,
                                @jakarta.validation.constraints.Size(max = 60) String type,
                                @jakarta.validation.constraints.Size(max = 40) String order) {}

    /** Renglón revisado (y posiblemente corregido) por el usuario en la previsualización. */
    public record CommitGroup(
            @jakarta.validation.constraints.NotBlank String requestId,
            boolean include,
            Long recipientId,
            @jakarta.validation.constraints.NotBlank(message = "Falta el nombre del cliente") @jakarta.validation.constraints.Size(max = 120) String clientName,
            @jakarta.validation.Valid AddressDto address,
            @jakarta.validation.constraints.Size(max = 80) String orders,
            @jakarta.validation.constraints.Size(max = 20) String route,
            @jakarta.validation.constraints.Size(max = 20) String subRoute,
            @jakarta.validation.constraints.Pattern(regexp = "^([A-Za-z]{2,5})?$", message = "Destino: 2 a 5 letras") String destinationCode,
            Long shippingRouteId,
            @jakarta.validation.constraints.Size(max = 500) String routeChangeReason,
            @jakarta.validation.constraints.Size(max = 50) List<@jakarta.validation.Valid CommitItem> items,
            @jakarta.validation.constraints.Size(max = 2000) List<@jakarta.validation.Valid CommitPackage> packages,
            @jakarta.validation.constraints.Size(max = 1000) String notes,
            boolean allowDuplicateOrder) {}

    public record CommitRequest(
            Long senderId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String fileKey,
            @jakarta.validation.constraints.Size(max = 200) String fileName,
            @jakarta.validation.constraints.Size(max = 60) String load,
            Integer declaredPackages,
            boolean confirm,
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max = 2000) List<@jakarta.validation.Valid CommitGroup> groups) {}

    /** CREATED, SKIPPED_EXISTING (ya generada con este archivo), SKIPPED_DUPLICATE (pedido en otra guía), EXCLUDED, REJECTED. */
    public record CommitRowResult(String requestId, String clientName, String outcome, String message, Long guideId, String folio,
                                  int pieces, String warning) {}

    public record CommitResult(Long batchId, int created, int skipped, int excluded, int rejected, Integer declaredPackages,
                               int committedPieces, int excludedPieces, List<CommitRowResult> rows) {}

    /** Bloque del reporte: un cliente con un pedido y sus bultos. */
    private static final class Block {
        final String name;
        final String order;
        final StringBuilder address = new StringBuilder();
        final String load;
        final String route;
        final List<String[]> packages = new ArrayList<>(); // {lpn, tipo}

        Block(String name, String order, String address, String load, String route) {
            this.name = name;
            this.order = order;
            this.address.append(address);
            this.load = load;
            this.route = route;
        }
    }

    @Transactional(readOnly = true)
    public ImportPreview preview(MultipartFile file, Long senderId) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("EMPTY_FILE", "El archivo está vacío.");
        if (file.getSize() > MAX_BYTES) throw ApiException.badRequest("FILE_TOO_LARGE", "El archivo supera 10 MB.");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_FILE", "No se pudo leer el archivo.");
        }
        Sender sender = senders.requireAccessible(senderId);
        Parsed parsed = parse(data);
        if (parsed.blocks.isEmpty()) {
            throw ApiException.badRequest("NO_PACKAGES", "El archivo no tiene bultos (columna «LPN») para generar guías.");
        }

        // Agrupa por cliente (mismo nombre normalizado): una guía por cliente con todos sus pedidos y bultos.
        Map<String, List<Block>> byClient = new LinkedHashMap<>();
        for (Block b : parsed.blocks) byClient.computeIfAbsent(key(b.name), k -> new ArrayList<>()).add(b);

        List<Recipient> catalog = recipients.findBySenderIdAndActiveTrue(sender.getId());
        Map<String, Recipient> exact = new HashMap<>();
        Map<String, Recipient> words = new HashMap<>();
        for (Recipient r : catalog) {
            exact.putIfAbsent(key(r.fullName()), r);
            words.putIfAbsent(wordsKey(r.fullName()), r);
        }

        SalterPrincipal p = CurrentUser.get();
        String fileKey = parsed.load != null ? parsed.load : sha(data);
        List<ImportGroup> groups = new ArrayList<>();
        Map<String, Integer> destinations = new HashMap<>();
        for (Map.Entry<String, List<Block>> e : byClient.entrySet()) {
            List<Block> blocks = e.getValue();
            Block first = blocks.get(0);
            List<String> orders = blocks.stream().map(b -> b.order).filter(o -> o != null && !o.isBlank()).distinct().toList();
            List<String> lpns = new ArrayList<>();
            List<PackageLine> lines = new ArrayList<>();
            Map<String, Integer> types = new LinkedHashMap<>();
            for (Block b : blocks) {
                for (String[] pk : b.packages) {
                    if (pk[0] != null) lpns.add(pk[0]);
                    String type = pk[1] == null || pk[1].isBlank() ? "Bulto" : capitalize(pk[1]);
                    types.merge(type, 1, Integer::sum);
                    lines.add(new PackageLine(pk[0], type, b.order));
                }
            }
            int count = blocks.stream().mapToInt(b -> b.packages.size()).sum();
            String addressText = first.address.toString().replaceAll("\\s+", " ").trim();

            Recipient match = exact.get(e.getKey());
            String how = match != null ? "EXACT" : null;
            if (match == null) {
                match = words.get(wordsKey(first.name));
                if (match != null) how = "WORDS";
            }
            AddressDto parsedAddress = parseAddress(addressText);
            if (match == null) {
                // Mismo cliente con el nombre escrito distinto (ej. "JIMENEZ AMAYA MARGARITOREFACCIONARIA JIMENEZ"):
                // comparte 2+ palabras del nombre Y el mismo C.P. con UN solo cliente del catálogo.
                match = probable(first.name, parsedAddress.zip(), catalog);
                if (match != null) how = "PROBABLE";
            }
            List<RecipientDto> suggestions = match != null ? List.of() : suggest(first.name, catalog);
            String route = first.route != null ? first.route : parsed.route;
            // Cliente nuevo: subruta que tienen TODOS los clientes del catálogo del mismo municipio y ruta (ej. Jiutepec, 74 → CVA-04).
            String suggestedSubRoute = match != null ? null : subRouteByCity(parsedAddress.city(), route, catalog);
            String plazaSource = match != null ? match.getSubRoute() : suggestedSubRoute;
            if (plazaSource != null) {
                String dest = RecipientDtos.plazaOf(plazaSource);
                if (dest != null) destinations.merge(dest, 1, Integer::sum);
            }

            UUID requestId = UUID.nameUUIDFromBytes((sender.getId() + "|" + fileKey + "|" + e.getKey()).getBytes(StandardCharsets.UTF_8));
            Optional<Guide> existing = guides.findByCreatedByIdAndClientRequestId(p.userId(), requestId);
            // Duplicados: los mismos pedidos ya están en otra guía vigente del remitente (de cualquier usuario o archivo).
            List<String> dupOrders = List.of();
            if (existing.isEmpty()) {
                List<Guide> dups = guideService.findDuplicateOrders(sender.getId(), orders, null);
                if (!dups.isEmpty()) {
                    existing = Optional.of(dups.get(0));
                    dupOrders = orders.stream().filter(o -> dups.stream().anyMatch(d -> GuideService.orderNumbers(d.getOrderReference()).contains(o))).toList();
                }
            }
            String notes = Text.truncate(Text.join(parsed.load != null ? "Carga " + parsed.load + "." : null,
                    lpns.isEmpty() ? null : "LPN: " + String.join(", ", lpns)), 1000);

            groups.add(new ImportGroup(requestId.toString(), first.name, orders, addressText, parsedAddress, count,
                    types.entrySet().stream().map(t -> new TypeCount(t.getKey(), t.getValue())).toList(), lpns, route, notes,
                    how, match == null ? null : RecipientDto.of(match), suggestions,
                    existing.map(Guide::getId).orElse(null), existing.map(Guide::displayReference).orElse(null), dupOrders, lines,
                    suggestedSubRoute));
        }
        groups.sort(Comparator.comparing(ImportGroup::clientName, String.CASE_INSENSITIVE_ORDER));
        String defaultDestination = destinations.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        int total = groups.stream().mapToInt(ImportGroup::packages).sum();
        return new ImportPreview(file.getOriginalFilename(), sender.getId(), sender.getName(), parsed.load, parsed.route,
                parsed.declared, total, defaultDestination, groups, fileKey,
                sender.getDefaultShippingRoute() == null ? null : sender.getDefaultShippingRoute().getId());
    }

    /**
     * Convierte la previsualización REVISADA en guías reales. Cada guía se crea en su propia transacción (un renglón con
     * error no revierte los demás) reutilizando todas las reglas de {@link GuideService#create} (folio, duplicados por
     * pedido, idempotencia por clientRequestId, agrupación en manifiesto). Reintentar con el mismo archivo no duplica.
     */
    public CommitResult commit(CommitRequest req) {
        SalterPrincipal p = CurrentUser.get();
        Sender sender = senders.requireAccessible(req.senderId());
        Long senderId = sender.getId();
        Long batchId = tx.execute(st -> batches.findBySenderIdAndFileKey(senderId, req.fileKey()).map(GuideImportBatch::getId).orElseGet(() -> {
            GuideImportBatch b = new GuideImportBatch();
            b.setSenderId(senderId);
            b.setFileKey(req.fileKey());
            b.setFileName(Text.truncate(Text.trimToNull(req.fileName()), 200));
            b.setLoadCode(Text.truncate(Text.trimToNull(req.load()), 60));
            b.setDeclaredPackages(req.declaredPackages());
            b.setCreatedBy(p.userId());
            return batches.saveAndFlush(b).getId();
        }));
        boolean canCatalog = p.has(mx.salter.api.security.Permission.RECIPIENTS_MANAGE);
        List<CommitRowResult> rows = new ArrayList<>();
        int created = 0, skipped = 0, excluded = 0, rejected = 0, pieces = 0, excludedPieces = 0;
        for (CommitGroup grp : req.groups()) {
            int groupPieces = grp.items() == null ? 0 : grp.items().stream().mapToInt(i -> i.quantity() == null ? 0 : i.quantity()).sum();
            if (!grp.include()) {
                excluded++;
                excludedPieces += groupPieces;
                rows.add(new CommitRowResult(grp.requestId(), grp.clientName(), "EXCLUDED", "No seleccionada.", null, null, groupPieces, null));
                continue;
            }
            CommitRowResult r = commitOne(req, grp, senderId, batchId, canCatalog, groupPieces);
            rows.add(r);
            switch (r.outcome()) {
                case "CREATED" -> { created++; pieces += r.pieces(); }
                case "SKIPPED_EXISTING", "SKIPPED_DUPLICATE" -> skipped++;
                default -> rejected++;
            }
        }
        int fc = created, fs = skipped, fr = rejected;
        tx.executeWithoutResult(st -> audit.record("GUIDES_IMPORTED", "IMPORT_BATCH", batchId,
                Map.of("created", fc, "skipped", fs, "rejected", fr, "fileKey", req.fileKey())));
        return new CommitResult(batchId, created, skipped, excluded, rejected, req.declaredPackages(), pieces, excludedPieces, rows);
    }

    private CommitRowResult commitOne(CommitRequest req, CommitGroup grp, Long senderId, Long batchId, boolean canCatalog, int groupPieces) {
        UUID requestId;
        try {
            requestId = UUID.fromString(grp.requestId());
        } catch (IllegalArgumentException e) {
            return new CommitRowResult(grp.requestId(), grp.clientName(), "REJECTED", "Identificador de renglón no válido.", null, null, groupPieces, null);
        }
        if (grp.items() == null || grp.items().isEmpty()) {
            return new CommitRowResult(grp.requestId(), grp.clientName(), "REJECTED", "Agrega al menos un tipo de bulto con cantidad.", null, null, 0, null);
        }
        SalterPrincipal p = CurrentUser.get();
        // Reintento del mismo usuario con el mismo archivo: devuelve la guía ya generada.
        Optional<Guide> already = guides.findByCreatedByIdAndClientRequestId(p.userId(), requestId);
        if (already.isPresent()) {
            Guide g = already.get();
            return new CommitRowResult(grp.requestId(), grp.clientName(), "SKIPPED_EXISTING", "Ya se había generado con este archivo.",
                    g.getId(), g.getFolio(), g.pieces(), null);
        }
        try {
            GuideDtos.GuideDto dto = tx.execute(st -> {
                Long recipientId = grp.recipientId();
                GuideDtos.NewRecipientInput oneTime = null;
                if (recipientId == null) {
                    if (canCatalog) {
                        recipientId = createOrMatchRecipient(senderId, grp);
                    } else {
                        oneTime = new GuideDtos.NewRecipientInput(Text.truncate(grp.clientName().trim(), 120), null, null, null,
                                grp.address(), false, true);
                    }
                }
                List<GuideDtos.ItemInput> items = grp.items().stream()
                        .map(i -> new GuideDtos.ItemInput(null, Text.truncate(i.type().trim(), 60), java.math.BigDecimal.valueOf(i.quantity()), "PIEZA", null))
                        .toList();
                GuideDtos.GuideRequest gr = new GuideDtos.GuideRequest(requestId, senderId, null, recipientId, oneTime, null,
                        Text.truncate(Text.trimToNull(grp.orders()), 80), null,
                        new GuideDtos.ShippingInput(null, Text.trimToNull(grp.destinationCode()), null, null, null),
                        items, Text.truncate(Text.trimToNull(grp.notes()), 1000), req.confirm(), null, grp.allowDuplicateOrder(),
                        grp.subRoute(), grp.shippingRouteId(), grp.routeChangeReason());
                GuideDtos.CreateResult cr = guideService.create(gr);
                if (cr.created()) {
                    guides.findById(cr.guide().id()).ifPresent(g -> g.setImportBatchId(batchId));
                    if (grp.packages() != null) {
                        for (CommitPackage pk : grp.packages()) {
                            String type = Text.trimToNull(pk.type());
                            packageRepo.save(new GuidePackage(cr.guide().id(), Text.trimToNull(pk.lpn()), type == null ? "Bulto" : type,
                                    Text.trimToNull(pk.order())));
                        }
                    }
                }
                return cr.guide();
            });
            String warning = grp.packages() != null && !grp.packages().isEmpty() && dto.packageCount() != null
                    && grp.packages().size() != dto.packageCount()
                    ? "El archivo trae " + grp.packages().size() + " LPN y la guía " + dto.packageCount() + " piezas." : null;
            return new CommitRowResult(grp.requestId(), grp.clientName(), "CREATED", null, dto.id(), dto.folio(),
                    dto.packageCount() == null ? 0 : dto.packageCount(), warning);
        } catch (ApiException e) {
            if ("DUPLICATE_ORDER".equals(e.getCode())) {
                Object gs = e.getExtra().get("guides");
                String ref = gs instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m ? String.valueOf(m.get("reference")) : null;
                return new CommitRowResult(grp.requestId(), grp.clientName(), "SKIPPED_DUPLICATE", e.getMessage(), null, ref, groupPieces, null);
            }
            return new CommitRowResult(grp.requestId(), grp.clientName(), "REJECTED", e.getMessage(), null, null, groupPieces, null);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Envío simultáneo del mismo renglón: la otra transacción ya lo creó.
            Optional<Guide> again = guides.findByCreatedByIdAndClientRequestId(p.userId(), requestId);
            if (again.isPresent()) {
                return new CommitRowResult(grp.requestId(), grp.clientName(), "SKIPPED_EXISTING", "Ya se había generado.", again.get().getId(),
                        again.get().getFolio(), again.get().pieces(), null);
            }
            return new CommitRowResult(grp.requestId(), grp.clientName(), "REJECTED", "No se pudo guardar la guía.", null, null, groupPieces, null);
        }
    }

    /**
     * Alta del cliente en el catálogo con la dirección del archivo; si ya existe exactamente, se usa el existente.
     * Los duplicados se revisan ANTES de crear para no marcar la transacción del renglón como fallida.
     */
    private Long createOrMatchRecipient(Long senderId, CommitGroup grp) {
        String name = Text.truncate(grp.clientName().trim(), 120);
        AddressDto a = grp.address();
        var check = recipientService.checkDuplicates(new mx.salter.api.recipient.RecipientDtos.DuplicateCheckRequest(senderId, name, null, null,
                a == null ? null : a.street(), a == null ? null : a.zip(), null));
        if (check.exactDuplicate()) {
            for (var m : check.matches()) if (m.recipientId() != null) return m.recipientId();
        }
        return recipientService.create(new mx.salter.api.recipient.RecipientDtos.RecipientRequest(senderId, name, null, null, null, a, null, null,
                Text.truncate(Text.trimToNull(grp.route()), 20), Text.truncate(Text.trimToNull(grp.subRoute()), 20), true, null,
                null, null, null)).id();
    }

    private record Parsed(List<Block> blocks, String load, String route, Integer declared) {}

    private static Parsed parse(byte[] data) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.forLanguageTag("es-MX"));
            Map<String, Integer> cols = findHeader(sheet, fmt);
            if (cols == null) {
                throw ApiException.badRequest("UNKNOWN_FORMAT",
                        "No se reconoce el formato: el archivo debe tener las columnas «Nombre de cliente», «Pedido» y «LPN».");
            }
            List<Block> blocks = new ArrayList<>();
            Block current = null;
            String load = null;
            String route = null;
            Integer declared = null;
            int rows = 0;
            for (Row row : sheet) {
                if (++rows > MAX_ROWS) throw ApiException.badRequest("TOO_MANY_ROWS", "El archivo tiene demasiadas filas.");
                String name = cell(row, cols.get("name"), fmt);
                String order = cell(row, cols.get("order"), fmt);
                String address = cell(row, cols.get("address"), fmt);
                String lpn = cell(row, cols.get("lpn"), fmt);
                String type = cell(row, cols.get("type"), fmt);
                String rowLoad = cell(row, cols.get("load"), fmt);
                String rowRoute = cell(row, cols.get("route"), fmt);
                if (Text.normalize(name).equals("nombre de cliente")) continue; // encabezado repetido en cada página
                Matcher total = TOTAL.matcher(Text.normalize(String.join(" ", name, order, address, lpn)));
                if (total.find()) {
                    declared = Integer.parseInt(total.group(1));
                    continue;
                }
                if (!rowLoad.isEmpty() && load == null) load = rowLoad;
                if (!rowRoute.isEmpty() && route == null) route = rowRoute;
                if (!name.isEmpty()) {
                    current = new Block(name, order, address, rowLoad.isEmpty() ? null : rowLoad, rowRoute.isEmpty() ? null : rowRoute);
                    blocks.add(current);
                    if (!lpn.isEmpty() || !type.isEmpty()) current.packages.add(new String[] {lpn.isEmpty() ? null : lpn, type});
                } else if (current != null && (!lpn.isEmpty() || !type.isEmpty())) {
                    current.packages.add(new String[] {lpn.isEmpty() ? null : lpn, type});
                } else if (current != null && !address.isEmpty()) {
                    current.address.append(' ').append(address); // dirección partida en dos filas
                }
            }
            blocks.removeIf(b -> b.packages.isEmpty());
            return new Parsed(blocks, load, route, declared);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException ae) throw ae;
            throw ApiException.badRequest("INVALID_FILE", "No se pudo leer el archivo. Usa el Excel del reporte de carga (.xlsx o .xls).");
        }
    }

    /** Busca la fila de encabezados en cualquier parte de la hoja (la primera página puede venir sin ella). */
    private static Map<String, Integer> findHeader(Sheet sheet, DataFormatter fmt) {
        for (Row row : sheet) {
            Map<String, Integer> cols = new HashMap<>();
            for (Cell c : row) {
                String h = Text.normalize(fmt.formatCellValue(c));
                int i = c.getColumnIndex();
                switch (h) {
                    case "nombre de cliente", "cliente", "nombre del cliente" -> cols.putIfAbsent("name", i);
                    case "pedido", "pedidos", "no pedido", "numero de pedido" -> cols.putIfAbsent("order", i);
                    case "direccion" -> cols.putIfAbsent("address", i);
                    case "lpn" -> cols.putIfAbsent("lpn", i);
                    case "tipo lpn", "tipo", "tipo de bulto" -> cols.putIfAbsent("type", i);
                    case "carga" -> cols.putIfAbsent("load", i);
                    case "ruta" -> cols.putIfAbsent("route", i);
                    default -> { }
                }
            }
            if (cols.containsKey("name") && (cols.containsKey("lpn") || cols.containsKey("type"))) return cols;
        }
        return null;
    }

    private static String cell(Row row, Integer col, DataFormatter fmt) {
        if (col == null) return "";
        Cell c = row.getCell(col);
        if (c == null) return "";
        return fmt.formatCellValue(c).replace(' ', ' ').replace("�", "").trim();
    }

    /** "CARR ZACATEPEC ZAPATA, SN, Santa Rosa Treinta, Tlaltizapán de Zapata, , Mexico, Morelos, 62772" → partes. */
    static AddressDto parseAddress(String text) {
        List<String> parts = new ArrayList<>(Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        String zip = null;
        String state = null;
        if (!parts.isEmpty() && ZIP.matcher(parts.get(parts.size() - 1)).matches()) zip = parts.remove(parts.size() - 1);
        if (!parts.isEmpty()) state = parts.remove(parts.size() - 1);
        if (!parts.isEmpty() && Text.normalize(parts.get(parts.size() - 1)).equals("mexico")) parts.remove(parts.size() - 1);
        if (state != null && Text.normalize(state).equals("mexico")) state = parts.isEmpty() ? null : parts.remove(parts.size() - 1);
        String street = parts.isEmpty() ? null : parts.remove(0);
        // El número exterior suele venir como segunda parte ("SN", "800 A", "MZ 22").
        if (street != null && !parts.isEmpty() && parts.get(0).length() <= 14 && (parts.get(0).matches(".*\\d.*") || parts.get(0).equalsIgnoreCase("SN"))) {
            street = street + " " + parts.remove(0);
        }
        String neighborhood = parts.isEmpty() ? null : parts.remove(0);
        String city = parts.isEmpty() ? null : parts.remove(0);
        return new AddressDto(cut(street, 200), cut(neighborhood, 120), cut(city, 120), cut(state, 120), zip, null);
    }

    private static String cut(String v, int max) {
        return v == null ? null : Text.truncate(v, max);
    }

    private static String key(String name) {
        return String.join(" ", words(name));
    }

    /** Mismas palabras en cualquier orden: "FLORES ARAUJO CLARA" = "CLARA FLORES ARAUJO". */
    private static String wordsKey(String name) {
        return String.join(" ", new TreeSet<>(words(name)));
    }

    private static List<String> words(String name) {
        return Arrays.stream(Text.normalize(name).replace('.', ' ').replace('-', ' ').split(" ")).filter(w -> !w.isBlank()).toList();
    }

    private static long sharedWords(String name, Recipient r) {
        List<String> mine = words(name).stream().filter(w -> w.length() > 2).toList();
        return words(r.fullName()).stream().filter(mine::contains).distinct().count();
    }

    /** Un único cliente del catálogo con 2+ palabras del nombre en común y el mismo código postal. */
    static Recipient probable(String name, String zip, List<Recipient> catalog) {
        if (zip == null || zip.isBlank()) return null;
        List<Recipient> hits = catalog.stream()
                .filter(r -> r.getAddress() != null && zip.equals(r.getAddress().getZip()))
                .filter(r -> sharedWords(name, r) >= 2)
                .toList();
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** Subruta común a todos los clientes del catálogo con el mismo municipio y ruta; null si no hay o hay varias. */
    static String subRouteByCity(String city, String route, List<Recipient> catalog) {
        if (city == null || city.isBlank()) return null;
        String c = Text.normalize(city);
        String rt = route == null ? null : route.trim();
        java.util.Set<String> found = new TreeSet<>();
        for (Recipient r : catalog) {
            if (r.getSubRoute() == null || r.getAddress() == null || !c.equals(Text.normalize(r.getAddress().getCity()))) continue;
            if (rt != null && r.getRoute() != null && !rt.equalsIgnoreCase(r.getRoute().trim())) continue;
            found.add(r.getSubRoute());
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /** Sugerencias cuando no hay coincidencia: clientes del catálogo que comparten 2+ palabras del nombre. */
    private static List<RecipientDto> suggest(String name, List<Recipient> catalog) {
        List<String> mine = words(name).stream().filter(w -> w.length() > 2).toList();
        if (mine.size() < 2) return List.of();
        record Scored(Recipient r, long score) {}
        return catalog.stream()
                .map(r -> new Scored(r, words(r.fullName()).stream().filter(mine::contains).distinct().count()))
                .filter(s -> s.score() >= 2)
                .sorted(Comparator.comparingLong(Scored::score).reversed())
                .limit(3)
                .map(s -> RecipientDto.of(s.r()))
                .collect(Collectors.toList());
    }

    private static String capitalize(String v) {
        String t = v.trim().toLowerCase(Locale.forLanguageTag("es-MX"));
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String sha(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
