package mx.salter.api.guide;

import static mx.salter.api.common.PdfSupport.labeled;
import static mx.salter.api.common.PdfSupport.td;
import static mx.salter.api.common.PdfSupport.th;
import static mx.salter.api.common.PdfSupport.v;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PdfSupport;
import mx.salter.api.common.Text;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documento de guía con plantilla PROVISIONAL. El diseño, el tamaño (pdf.guide.pageSize),
 * el número de copias (pdf.guide.copies) y el pie (pdf.guide.footer) se configuran en app_settings.
 * No incluye QR, código de barras ni campos fiscales hasta que el cliente los confirme.
 */
@Service
public class GuidePdfService {
    public record Rendered(String filename, byte[] bytes) {}

    private final GuideRepository guides;
    private final GuideAccess access;
    private final SettingsService settings;
    private final AuditService audit;
    private final ZoneId zone;

    public GuidePdfService(GuideRepository guides, GuideAccess access, SettingsService settings, AuditService audit, ZoneId zone) {
        this.guides = guides;
        this.access = access;
        this.settings = settings;
        this.audit = audit;
        this.zone = zone;
    }

    @Transactional
    public Rendered render(Long id, SalterPrincipal p) {
        Guide g = guides.findDetailed(id).orElseThrow(() -> ApiException.notFound("Guía"));
        access.requireView(p, g);
        if (g.getStatus() == GuideStatus.BORRADOR || g.getStatus() == GuideStatus.CANCELADA) {
            throw ApiException.business("PDF_NOT_AVAILABLE", "El documento sólo está disponible para guías confirmadas.");
        }
        int copies = Math.max(1, Math.min(4, Integer.parseInt(settings.get("pdf.guide.copies", "1"))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PdfSupport.pageSize(settings.get("pdf.guide.pageSize", "LETTER")), 30, 30, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.addTitle("Guía " + g.getFolio());
        doc.addCreator("SALTER");
        doc.open();
        for (int c = 0; c < copies; c++) {
            if (c > 0) doc.newPage();
            write(doc, g);
        }
        doc.close();
        audit.record("GUIDE_PDF", "GUIDE", g.getId(), Map.of("folio", g.getFolio()));
        return new Rendered("guia-" + g.getFolio() + ".pdf", out.toByteArray());
    }

    private void write(Document doc, Guide g) {
        PdfSupport.header(doc, "Guía de envío", settings.get("company.name", "SALTER") + " · " + settings.get("company.slogan", ""));

        PdfPTable top = new PdfPTable(new float[]{2, 1.3f, 1.3f, 1.3f});
        top.setWidthPercentage(100);
        PdfPCell folio = new PdfPCell();
        folio.setPadding(6);
        folio.setBackgroundColor(PdfSupport.LIGHT);
        folio.addElement(new Paragraph("FOLIO", PdfSupport.LABEL));
        folio.addElement(new Paragraph(g.getFolio(), PdfSupport.MONO));
        top.addCell(folio);
        top.addCell(labeled("Fecha de elaboración", PdfSupport.fmt(g.getCreatedAt(), zone)));
        top.addCell(labeled("Confirmada", PdfSupport.fmt(g.getConfirmedAt(), zone)));
        top.addCell(labeled("Turno", g.getShift() == null ? null : g.getShift().getName()));
        doc.add(top);

        PdfPTable parties = new PdfPTable(2);
        parties.setWidthPercentage(100);
        parties.setSpacingBefore(8);
        parties.addCell(party("Remitente", g.getSenderName(), g.getSenderContact(), g.getSenderPhone(),
                Text.join(g.getSenderStreet(), g.getSenderNeighborhood()), Text.join(g.getSenderCity(), g.getSenderState(), g.getSenderZip()), null));
        parties.addCell(party("Destinatario", g.getRcptName(), null, g.getRcptPhone(),
                Text.join(g.getRcptStreet(), g.getRcptNeighborhood()), Text.join(g.getRcptCity(), g.getRcptState(), g.getRcptZip()), g.getRcptRefs()));
        doc.add(parties);

        PdfPTable refs = new PdfPTable(3);
        refs.setWidthPercentage(100);
        refs.setSpacingBefore(8);
        refs.addCell(labeled("Referencia CD", g.getCdReference()));
        refs.addCell(labeled("Pedido", g.getOrderReference()));
        refs.addCell(labeled("Bultos", String.valueOf(g.getPackageCount())));
        doc.add(refs);

        doc.add(PdfSupport.section("Mercancía"));
        PdfPTable items = new PdfPTable(new float[]{0.5f, 4, 1.2f, 1.2f, 1.2f});
        items.setWidthPercentage(100);
        items.setHeaderRows(1);
        for (String h : new String[]{"#", "Descripción", "Cantidad", "Unidad", "Peso (kg)"}) items.addCell(th(h));
        for (GuideItem i : g.getItems()) {
            items.addCell(td(String.valueOf(i.getPosition())));
            items.addCell(td(i.getDescription()));
            items.addCell(td(i.getQuantity().stripTrailingZeros().toPlainString()));
            items.addCell(td(i.getUnit()));
            items.addCell(td(i.getWeightKg() == null ? null : i.getWeightKg().toPlainString()));
        }
        doc.add(items);

        if (g.getNotes() != null) {
            doc.add(PdfSupport.section("Observaciones"));
            doc.add(new Paragraph(g.getNotes(), PdfSupport.TEXT));
        }

        PdfPTable signs = new PdfPTable(2);
        signs.setWidthPercentage(100);
        signs.setSpacingBefore(36);
        signs.addCell(signature("Entrega (remitente)"));
        signs.addCell(signature("Recibe (nombre y firma)"));
        doc.add(signs);

        Paragraph foot = new Paragraph(settings.get("pdf.guide.footer", ""), PdfSupport.SMALL);
        foot.setSpacingBefore(14);
        foot.setAlignment(Element.ALIGN_CENTER);
        doc.add(foot);
    }

    private static PdfPCell party(String title, String name, String contact, String phone, String line1, String line2, String refs) {
        PdfPCell c = new PdfPCell();
        c.setPadding(7);
        c.setBorderColor(new java.awt.Color(0xD5, 0xDE, 0xEA));
        c.addElement(new Paragraph(title.toUpperCase(), PdfSupport.H2));
        c.addElement(new Paragraph(v(name), PdfSupport.TEXT));
        if (contact != null) c.addElement(new Paragraph("Contacto: " + contact, PdfSupport.SMALL));
        c.addElement(new Paragraph("Tel.: " + v(phone), PdfSupport.SMALL));
        c.addElement(new Paragraph(v(line1), PdfSupport.SMALL));
        c.addElement(new Paragraph(v(line2), PdfSupport.SMALL));
        if (refs != null) c.addElement(new Paragraph("Referencias: " + refs, PdfSupport.SMALL));
        return c;
    }

    private static PdfPCell signature(String label) {
        PdfPCell c = new PdfPCell();
        c.setBorder(com.lowagie.text.Rectangle.TOP);
        c.setPaddingTop(4);
        Paragraph p = new Paragraph(label, PdfSupport.SMALL);
        p.setAlignment(Element.ALIGN_CENTER);
        c.addElement(p);
        return c;
    }
}
