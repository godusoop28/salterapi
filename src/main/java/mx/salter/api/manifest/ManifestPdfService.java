package mx.salter.api.manifest;

import static mx.salter.api.common.PdfSupport.labeled;
import static mx.salter.api.common.PdfSupport.td;
import static mx.salter.api.common.PdfSupport.th;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PdfSupport;
import mx.salter.api.common.Text;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manifiesto en PDF. PLANTILLA PROVISIONAL: el cliente aún no entrega un formato oficial de manifiesto.
 * Incluye logotipo, ruta de embarque, fecha y turno, guías con pedidos, piezas y tipos de bulto, totales,
 * guías que no viajan, cambios de ruta, autorización y firmas.
 */
@Service
public class ManifestPdfService {
    public record Rendered(String filename, byte[] bytes) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ManifestRepository manifests;
    private final DispatchRepository dispatches;
    private final ManifestService manifestService;
    private final SettingsService settings;
    private final AuditService audit;
    private final ZoneId zone;

    public ManifestPdfService(ManifestRepository manifests, DispatchRepository dispatches, ManifestService manifestService,
                              SettingsService settings, AuditService audit, ZoneId zone) {
        this.manifests = manifests;
        this.dispatches = dispatches;
        this.manifestService = manifestService;
        this.settings = settings;
        this.audit = audit;
        this.zone = zone;
    }

    @Transactional
    public Rendered render(Long id) {
        Manifest m = manifests.findDetailed(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        ManifestDtos.ReviewDto r = manifestService.review(m);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PdfSupport.pageSize(settings.get("pdf.manifest.pageSize", "LETTER")), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.addTitle("Manifiesto " + m.getNumber());
        doc.addCreator("SALTER");
        doc.open();
        String state = switch (m.getStatus()) {
            case BORRADOR -> "EN PREPARACIÓN — no válido para salida";
            case CERRADO -> m.getAuthorizedAt() == null ? "CERRADO — salida pendiente de autorización" : "CERRADO — salida AUTORIZADA";
            case DESPACHADO -> "DESPACHADO";
        };
        PdfSupport.header(doc, "Manifiesto " + m.getNumber(), state);

        PdfPTable info = new PdfPTable(4);
        info.setWidthPercentage(100);
        info.addCell(labeled("Ruta de embarque", m.getShippingRoute() == null ? "Sin ruta" : m.getShippingRoute().label()));
        info.addCell(labeled("Fecha de operación", m.getOperationDate().format(DATE)));
        info.addCell(labeled("Turno", m.getShift() == null ? null : m.getShift().getName()));
        info.addCell(labeled("Responsable de la ruta", m.getShippingRoute() == null || m.getShippingRoute().getResponsible() == null ? null
                : m.getShippingRoute().getResponsible().getFullName()));
        info.addCell(labeled("Elaboró", m.getCreatedBy().getFullName()));
        info.addCell(labeled("Cerró / confirmó", m.getClosedBy() == null ? null : m.getClosedBy().getFullName() + " · " + PdfSupport.fmt(m.getClosedAt(), zone)));
        info.addCell(labeled("Autorizó salida", m.getAuthorizedBy() == null ? "Pendiente"
                : m.getAuthorizedBy().getFullName() + " · " + PdfSupport.fmt(m.getAuthorizedAt(), zone)));
        var d = dispatches.findByManifestId(m.getId()).orElse(null);
        info.addCell(labeled("Despacho", d == null ? "Pendiente" : PdfSupport.fmt(d.getDispatchedAt(), zone)
                + (d.getVehicle() == null ? "" : " · " + d.getVehicle())));
        doc.add(info);

        PdfPTable totals = new PdfPTable(6);
        totals.setWidthPercentage(100);
        totals.setSpacingBefore(6);
        totals.addCell(labeled("Guías", String.valueOf(r.guides())));
        totals.addCell(labeled("Viajan / No viajan", r.travelling() + " / " + r.notTravelling()));
        totals.addCell(labeled("Pedidos", String.valueOf(r.orders())));
        totals.addCell(labeled("Cajas", String.valueOf(r.boxes())));
        totals.addCell(labeled("Piezas (viajan)", r.pieces() + " (" + r.travellingPieces() + ")"));
        totals.addCell(labeled("Bultos por tipo", r.piecesByType().stream().map(t -> t.quantity() + " " + t.type()).collect(Collectors.joining(", "))));
        doc.add(totals);

        doc.add(PdfSupport.section("Guías incluidas"));
        PdfPTable t = new PdfPTable(new float[]{0.35f, 1.3f, 2.1f, 0.8f, 1.5f, 1.6f, 0.6f, 0.7f});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String h : new String[]{"#", "Folio", "Cliente", "Subruta", "Pedido(s)", "Bultos", "Piezas", "Viaja"}) t.addCell(th(h));
        int i = 1;
        for (ManifestGuide mg : m.activeGuides()) {
            Guide g = mg.getGuide();
            t.addCell(td(String.valueOf(i++)));
            t.addCell(td(g.getFolio()));
            t.addCell(td(g.getRcptName()));
            t.addCell(td(g.getSubRoute()));
            t.addCell(td(g.getOrderReference()));
            t.addCell(td(g.getItems().stream().map(it -> it.getQuantity().intValue() + " " + it.getDescription()).collect(Collectors.joining(", "))));
            t.addCell(td(String.valueOf(g.pieces())));
            t.addCell(td(mg.isTravels() ? "SÍ" : "NO"));
        }
        doc.add(t);

        var notTravelling = m.getGuides().stream().filter(mg -> !mg.isTravels()).toList();
        if (!notTravelling.isEmpty()) {
            doc.add(PdfSupport.section("Guías que NO viajan (no se cuentan como despachadas)"));
            PdfPTable nt = new PdfPTable(new float[]{1.3f, 2.2f, 3f});
            nt.setWidthPercentage(100);
            for (String h : new String[]{"Folio", "Cliente", "Motivo"}) nt.addCell(th(h));
            for (ManifestGuide mg : notTravelling) {
                nt.addCell(td(mg.getGuide().getFolio()));
                nt.addCell(td(mg.getGuide().getRcptName()));
                nt.addCell(td(mg.getTravelReason()));
            }
            doc.add(nt);
        }

        if (!r.routeChanges().isEmpty()) {
            doc.add(PdfSupport.section("Cambios de ruta"));
            PdfPTable rc = new PdfPTable(new float[]{1.2f, 1.6f, 1.6f, 1.4f, 2f});
            rc.setWidthPercentage(100);
            for (String h : new String[]{"Folio", "Ruta anterior", "Ruta nueva", "Solicitó", "Motivo"}) rc.addCell(th(h));
            for (var c : r.routeChanges()) {
                rc.addCell(td(c.folio()));
                rc.addCell(td(c.from()));
                rc.addCell(td(c.to()));
                rc.addCell(td(c.by() + " · " + PdfSupport.fmt(c.at(), zone)));
                rc.addCell(td(c.reason()));
            }
            doc.add(rc);
        }

        if (!r.batches().isEmpty()) {
            doc.add(PdfSupport.section("Comparación con el archivo de carga"));
            PdfPTable bt = new PdfPTable(5);
            bt.setWidthPercentage(100);
            for (String h : new String[]{"Archivo / carga", "Bultos declarados", "En este manifiesto", "En otros / sin manifiesto", "Diferencia"}) bt.addCell(th(h));
            for (var b : r.batches()) {
                bt.addCell(td(Text.join(b.load(), b.fileName())));
                bt.addCell(td(b.declaredPackages() == null ? "—" : String.valueOf(b.declaredPackages())));
                bt.addCell(td(String.valueOf(b.piecesInManifest())));
                bt.addCell(td(b.piecesInOtherManifests() + " / " + b.piecesWithoutManifest()));
                bt.addCell(td(b.difference() == null ? "—" : String.valueOf(b.difference())));
            }
            doc.add(bt);
        }

        String obs = Text.join(m.getNotes(), m.getAuthorizationNotes() == null ? null : "Autorización: " + m.getAuthorizationNotes(),
                d == null || d.getNotes() == null ? null : "Despacho: " + d.getNotes());
        if (!obs.isBlank()) {
            doc.add(PdfSupport.section("Observaciones"));
            doc.add(new Paragraph(obs, PdfSupport.TEXT));
        }

        PdfPTable signs = new PdfPTable(4);
        signs.setWidthPercentage(100);
        signs.setSpacingBefore(40);
        String[][] who = {
                {"Elaboró", m.getCreatedBy().getFullName()},
                {"Confirmó (cierre)", m.getClosedBy() == null ? "" : m.getClosedBy().getFullName()},
                {"Autorizó salida (encargado)", m.getAuthorizedBy() == null ? "" : m.getAuthorizedBy().getFullName()},
                {"Recibe / transporta", ""}};
        for (String[] s : who) {
            PdfPCell c = new PdfPCell();
            c.setBorder(Rectangle.TOP);
            Paragraph name = new Paragraph(s[1], PdfSupport.TEXT);
            name.setAlignment(Element.ALIGN_CENTER);
            c.addElement(name);
            Paragraph p = new Paragraph(s[0], PdfSupport.SMALL);
            p.setAlignment(Element.ALIGN_CENTER);
            c.addElement(p);
            signs.addCell(c);
        }
        doc.add(signs);
        doc.close();
        audit.record("MANIFEST_PDF", "MANIFEST", m.getId(), Map.of("number", m.getNumber()));
        return new Rendered("manifiesto-" + m.getNumber() + ".pdf", out.toByteArray());
    }

    static int orders(Guide g) {
        return GuideService.orderNumbers(g.getOrderReference()).size();
    }
}
