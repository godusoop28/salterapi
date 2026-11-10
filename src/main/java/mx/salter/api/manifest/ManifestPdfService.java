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
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PdfSupport;
import mx.salter.api.common.Text;
import mx.salter.api.guide.Guide;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manifiesto en PDF con plantilla PROVISIONAL (se adaptará al ejemplo real del cliente). */
@Service
public class ManifestPdfService {
    public record Rendered(String filename, byte[] bytes) {}

    private final ManifestRepository manifests;
    private final DispatchRepository dispatches;
    private final SettingsService settings;
    private final AuditService audit;
    private final ZoneId zone;

    public ManifestPdfService(ManifestRepository manifests, DispatchRepository dispatches, SettingsService settings,
                              AuditService audit, ZoneId zone) {
        this.manifests = manifests;
        this.dispatches = dispatches;
        this.settings = settings;
        this.audit = audit;
        this.zone = zone;
    }

    @Transactional
    public Rendered render(Long id) {
        Manifest m = manifests.findDetailed(id).orElseThrow(() -> ApiException.notFound("Manifiesto"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PdfSupport.pageSize(settings.get("pdf.manifest.pageSize", "LETTER")), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.addTitle("Manifiesto " + m.getNumber());
        doc.open();
        PdfSupport.header(doc, "Manifiesto " + m.getNumber(),
                m.getStatus() == Manifest.Status.BORRADOR ? "BORRADOR — no válido para salida" : "Estado: " + m.getStatus().name());

        PdfPTable info = new PdfPTable(4);
        info.setWidthPercentage(100);
        info.addCell(labeled("Fecha de operación", m.getOperationDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        info.addCell(labeled("Turno", m.getShift() == null ? null : m.getShift().getName()));
        info.addCell(labeled("Remitente", m.getSender() == null ? "Varios" : m.getSender().getName()));
        info.addCell(labeled("Elaboró", m.getCreatedBy().getFullName()));
        info.addCell(labeled("Cerrado", PdfSupport.fmt(m.getClosedAt(), zone)));
        var d = dispatches.findByManifestId(m.getId()).orElse(null);
        info.addCell(labeled("Salida autorizada", d == null ? "Pendiente" : PdfSupport.fmt(d.getDispatchedAt(), zone)));
        info.addCell(labeled("Vehículo", d == null ? null : d.getVehicle()));
        int packages = m.getGuides().stream().mapToInt(mg -> mg.getGuide().getPackageCount()).sum();
        info.addCell(labeled("Guías / bultos", m.getGuides().size() + " / " + packages));
        doc.add(info);

        doc.add(PdfSupport.section("Guías incluidas"));
        PdfPTable t = new PdfPTable(new float[]{0.4f, 1.3f, 2.4f, 1.6f, 1.3f, 0.7f});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String h : new String[]{"#", "Folio", "Destinatario", "Destino", "Ref. CD / Pedido", "Bultos"}) t.addCell(th(h));
        int i = 1;
        for (ManifestGuide mg : m.getGuides()) {
            Guide g = mg.getGuide();
            t.addCell(td(String.valueOf(i++)));
            t.addCell(td(g.getFolio()));
            t.addCell(td(g.getRcptName()));
            t.addCell(td(Text.join(g.getRcptCity(), g.getRcptState())));
            t.addCell(td(Text.join(g.getCdReference(), g.getOrderReference() == null ? null : "/ " + g.getOrderReference())));
            t.addCell(td(String.valueOf(g.getPackageCount())));
        }
        doc.add(t);

        if (m.getNotes() != null) {
            doc.add(PdfSupport.section("Observaciones"));
            doc.add(new Paragraph(m.getNotes(), PdfSupport.TEXT));
        }

        PdfPTable signs = new PdfPTable(3);
        signs.setWidthPercentage(100);
        signs.setSpacingBefore(40);
        for (String s : new String[]{"Elaboró", "Autorizó salida", "Recibe / transporta"}) {
            PdfPCell c = new PdfPCell();
            c.setBorder(Rectangle.TOP);
            Paragraph p = new Paragraph(s, PdfSupport.SMALL);
            p.setAlignment(Element.ALIGN_CENTER);
            c.addElement(p);
            signs.addCell(c);
        }
        doc.add(signs);
        doc.close();
        audit.record("MANIFEST_PDF", "MANIFEST", m.getId(), Map.of("number", m.getNumber()));
        return new Rendered("manifiesto-" + m.getNumber() + ".pdf", out.toByteArray());
    }
}
