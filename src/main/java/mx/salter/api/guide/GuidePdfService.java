package mx.salter.api.guide;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BarcodePDF417;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PdfSupport;
import mx.salter.api.common.Text;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guía con el FORMATO OFICIAL del cliente (boceto "GUIA SALTER.xls"):
 * <pre>
 *  PBA ⇒ OAX                      [logo]
 *  AUTO TODO MEXICANA          (sólo el nombre del remitente)
 *  ───────────────────────────────────────
 *  C O N S I G N A T A R I O
 *  NOMBRE                 FECHA        10/11/26
 *  CALLE                  PESO            10 KG
 *  CIUDAD ESTADO          DIMENSIONES  30X20X15
 *  8 P I E Z A S                   C V A - 0 1   (subruta)
 *  ───────────────────────────────────────
 *  [ código PDF417 ]
 *  ┌ A T M   0 0 0 0 0 0 0 0 1 ───────────┐
 *  │ SERVICIO ORDINARIO   FECHA COMPROMISO │
 *  └─────────────────────────── 11/11/26 ──┘
 *  ─────────────          ─────────────
 *   N O M B R E               F I R M A
 * </pre>
 * Tamaño (pdf.guide.pageSize: LETTER o LABEL_4X6, o el parámetro size) y copias (pdf.guide.copies) configurables.
 *
 * <p>Dos presentaciones con la MISMA lógica de generación:
 * <ul>
 *   <li>{@link Variant#CLIENTE}: para el cliente. NO incluye nombre, domicilio ni teléfono de quien recibe, ni los campos
 *       de nombre y firma de recepción. Tampoco en metadatos, nombre de archivo ni código de barras.</li>
 *   <li>{@link Variant#INTERNA}: documento operativo completo (consignatario y firmas) para personal autorizado.</li>
 * </ul>
 */
@Service
public class GuidePdfService {
    public record Rendered(String filename, byte[] bytes) {}

    public enum Variant { CLIENTE, INTERNA }

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.forLanguageTag("es-MX"));
    private static final Color ARROW_FILL = new Color(0xDC, 0xEB, 0xF7);
    private static final Color ARROW_LINE = new Color(0x7A, 0x9C, 0xC6);
    /** Ancho del formato en puntos sobre hoja carta (el boceto mide ~ 11.7 cm). */
    private static final float LETTER_WIDTH = 400f;

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
        return render(id, p, Variant.CLIENTE, null);
    }

    /** Quién puede obtener la versión INTERNA (con datos de quien recibe): personal operativo. */
    public boolean canSeeInternal(SalterPrincipal p, Guide g) {
        return p.hasAny(mx.salter.api.security.Permission.GUIDES_VIEW_ALL, mx.salter.api.security.Permission.DELIVERIES_ANY,
                mx.salter.api.security.Permission.RECEPTION_CONFIRM) || access.isAssignedCourier(p, g);
    }

    @Transactional
    public Rendered render(Long id, SalterPrincipal p, Variant variant, String sizeParam) {
        Guide g = guides.findDetailed(id).orElseThrow(() -> ApiException.notFound("Guía"));
        access.requireView(p, g);
        if (g.getStatus() == GuideStatus.BORRADOR || g.getStatus() == GuideStatus.CANCELADA) {
            throw ApiException.business("PDF_NOT_AVAILABLE", "El documento sólo está disponible para guías confirmadas.");
        }
        Variant v = variant == null ? Variant.CLIENTE : variant;
        if (v == Variant.INTERNA && !canSeeInternal(p, g)) {
            throw ApiException.forbidden("La versión interna (con datos de quien recibe) es sólo para personal operativo.");
        }
        int copies = Math.max(1, Math.min(4, parseInt(settings.get("pdf.guide.copies", "1"), 1)));
        String size = (sizeParam == null || sizeParam.isBlank() ? settings.get("pdf.guide.pageSize", "LETTER") : sizeParam).trim();
        if (!size.equalsIgnoreCase("LETTER") && !size.equalsIgnoreCase("LABEL_4X6")) {
            throw ApiException.badRequest("INVALID_SIZE", "Tamaño no válido. Usa LETTER (carta) o LABEL_4X6 (etiqueta 4×6).");
        }
        boolean label = "LABEL_4X6".equalsIgnoreCase(size);
        Rectangle page = label ? new Rectangle(288, 432) : PdfSupport.pageSize(size);
        float side = label ? 10 : Math.max(20, (page.getWidth() - LETTER_WIDTH) / 2);
        float top = label ? 10 : 36;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(page, side, side, top, top);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        doc.addTitle("Guía " + printedFolio(g.getFolio()));
        doc.addCreator("SALTER");
        doc.open();
        float width = page.getWidth() - 2 * side;
        Layout l = new Layout(width / LETTER_WIDTH);
        for (int c = 0; c < copies; c++) {
            if (c > 0 && label) doc.newPage();
            PdfPTable sheet = sheet(writer, g, l, width, v);
            sheet.setKeepTogether(true);
            if (c > 0) sheet.setSpacingBefore(28 * l.k);
            doc.add(sheet);
        }
        doc.close();
        audit.record("GUIDE_PDF", "GUIDE", g.getId(), Map.of("folio", g.getFolio(), "variant", v.name(), "size", size.toUpperCase()));
        return new Rendered("guia-" + g.getFolio() + (v == Variant.CLIENTE ? "-cliente" : "-interna") + ".pdf", out.toByteArray());
    }

    /** Fuentes escaladas al ancho disponible (carta = 1.0). */
    private static final class Layout {
        final float k;
        final Font route, sender, caps, body, pieceNum, pieceTxt, cd, folio, service, sign;

        Layout(float k) {
            this.k = k;
            route = new Font(Font.HELVETICA, 26 * k, Font.NORMAL, Color.BLACK);
            sender = new Font(Font.HELVETICA, 9 * k, Font.NORMAL, Color.BLACK);
            caps = new Font(Font.HELVETICA, 7.5f * k, Font.NORMAL, Color.BLACK);
            body = new Font(Font.HELVETICA, 9.5f * k, Font.NORMAL, Color.BLACK);
            pieceNum = new Font(Font.HELVETICA, 36 * k, Font.NORMAL, Color.BLACK);
            pieceTxt = new Font(Font.HELVETICA, 17 * k, Font.NORMAL, Color.BLACK);
            cd = new Font(Font.HELVETICA, 14 * k, Font.NORMAL, Color.BLACK);
            folio = new Font(Font.HELVETICA, 18 * k, Font.NORMAL, Color.BLACK);
            service = new Font(Font.HELVETICA, 9.5f * k, Font.NORMAL, Color.BLACK);
            sign = new Font(Font.HELVETICA, 8 * k, Font.NORMAL, Color.BLACK);
        }
    }

    private PdfPTable sheet(PdfWriter writer, Guide g, Layout l, float width, Variant v) {
        PdfPTable t = new PdfPTable(1);
        t.setTotalWidth(width);
        t.setLockedWidth(true);
        t.addCell(plain(header(writer, g, l)));
        t.addCell(plain(consignee(g, l, v)));
        t.addCell(plain(pieces(g, l)));
        t.addCell(plain(barcode(g, l, width)));
        t.addCell(plain(folioBox(g, l)));
        if (v == Variant.INTERNA) t.addCell(plain(signatures(l)));
        return t;
    }

    // ── 1. Ruta, remitente y logo ──
    private PdfPTable header(PdfWriter writer, Guide g, Layout l) {
        PdfPTable t = new PdfPTable(new float[]{1.3f, 1f});
        t.setWidthPercentage(100);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.RIGHT | Rectangle.BOTTOM);
        left.setPadding(2);
        left.setPaddingBottom(5 * l.k);
        PdfPTable route = new PdfPTable(new float[]{1f, 0.9f, 1f});
        route.setWidthPercentage(100);
        route.addCell(text(code(g.getOriginCode()), l.route, Element.ALIGN_RIGHT, Rectangle.NO_BORDER));
        PdfPCell arrow = new PdfPCell(arrow(writer, l), false);
        arrow.setBorder(Rectangle.NO_BORDER);
        arrow.setHorizontalAlignment(Element.ALIGN_CENTER);
        arrow.setVerticalAlignment(Element.ALIGN_MIDDLE);
        arrow.setPaddingTop(5 * l.k);
        route.addCell(arrow);
        route.addCell(text(code(g.getDestinationCode()), l.route, Element.ALIGN_LEFT, Rectangle.NO_BORDER));
        left.addElement(route);
        // Sólo el nombre del remitente (ej. AUTO TODO MEXICANA). La ciudad/estado del remitente se conserva en la base,
        // pero no se imprime: duplicaba "PUEBLA PUEBLA" junto a la plaza de origen.
        Paragraph sender = new Paragraph(up(g.getSenderName()), l.sender);
        sender.setLeading(10.5f * l.k);
        left.addElement(sender);
        t.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.BOTTOM);
        right.setHorizontalAlignment(Element.ALIGN_CENTER);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);
        right.setPadding(4 * l.k);
        Image logo = logo(l);
        if (logo != null) {
            right.setImage(logo);
            right.setFixedHeight(68 * l.k);
        } else {
            right.setPhrase(new Phrase("SALTER", l.route));
        }
        t.addCell(right);
        return t;
    }

    private static Image arrow(PdfWriter writer, Layout l) {
        float w = 46 * l.k, h = 16 * l.k;
        PdfContentByte cb = writer.getDirectContent();
        PdfTemplate tpl = cb.createTemplate(w, h);
        float shaft = h * 0.32f, head = w * 0.3f;
        tpl.setColorFill(ARROW_FILL);
        tpl.setColorStroke(ARROW_LINE);
        tpl.setLineWidth(0.7f);
        tpl.moveTo(0.5f, h / 2 - shaft / 2);
        tpl.lineTo(w - head, h / 2 - shaft / 2);
        tpl.lineTo(w - head, 0.5f);
        tpl.lineTo(w - 0.5f, h / 2);
        tpl.lineTo(w - head, h - 0.5f);
        tpl.lineTo(w - head, h / 2 + shaft / 2);
        tpl.lineTo(0.5f, h / 2 + shaft / 2);
        tpl.closePathFillStroke();
        try {
            return Image.getInstance(tpl);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Image logo(Layout l) {
        try (var in = GuidePdfService.class.getResourceAsStream("/branding/salter-guia.png")) {
            if (in == null) return PdfSupport.logo();
            Image img = Image.getInstance(in.readAllBytes());
            img.scaleToFit(118 * l.k, 62 * l.k);
            return img;
        } catch (Exception e) {
            return PdfSupport.logo();
        }
    }

    // ── 2. Consignatario (sólo versión interna) + fecha, peso, dimensiones ──
    private PdfPTable consignee(Guide g, Layout l, Variant v) {
        PdfPTable t = new PdfPTable(new float[]{1.25f, 0.6f, 0.55f});
        t.setWidthPercentage(100);
        boolean internal = v == Variant.INTERNA;
        PdfPCell title = text(spaced(internal ? "CONSIGNATARIO." : "DATOS DEL ENVÍO."), l.caps, Element.ALIGN_LEFT, Rectangle.NO_BORDER);
        title.setColspan(3);
        title.setPaddingLeft(28 * l.k);
        title.setPaddingTop(8 * l.k);
        title.setPaddingBottom(8 * l.k);
        t.addCell(title);

        String line1, line2, line3;
        if (internal) {
            line1 = up(g.getRcptClientCode() == null ? g.getRcptName() : "#" + g.getRcptClientCode() + "  " + g.getRcptName());
            line2 = up(Text.join(g.getRcptStreet(), g.getRcptNeighborhood() == null ? null : "COL. " + g.getRcptNeighborhood()));
            line3 = up(Text.join(g.getRcptCity(), g.getRcptState(), g.getRcptZip() == null ? null : "C.P. " + g.getRcptZip()));
        } else {
            // Versión cliente: sólo datos operativos, sin nombre, domicilio ni contacto de quien recibe.
            line1 = "DESTINO " + code(g.getDestinationCode());
            line2 = g.getOrderReference() == null ? "" : "PEDIDO " + up(g.getOrderReference());
            line3 = g.getShippingRoute() == null ? "" : "RUTA DE EMBARQUE " + up(g.getShippingRoute().getCode());
        }
        LocalDate date = g.getConfirmedAt() == null ? null : g.getConfirmedAt().atZone(zone).toLocalDate();
        String[][] rows = {
                {line1, "FECHA", date == null ? "" : SHORT.format(date)},
                {line2, "PESO", g.isWeightNotApplicable() ? NOT_APPLICABLE : weight(g.getTotalWeightKg())},
                {line3, "DIMENSIONES", g.isDimensionsNotApplicable() ? NOT_APPLICABLE : up(g.getDimensions())},
        };
        for (String[] r : rows) {
            t.addCell(text(r[0], l.body, Element.ALIGN_LEFT, Rectangle.NO_BORDER));
            t.addCell(text(r[1], l.body, Element.ALIGN_RIGHT, Rectangle.NO_BORDER));
            t.addCell(text(r[2], l.body, Element.ALIGN_RIGHT, Rectangle.NO_BORDER));
        }
        if (internal && g.getRcptPhone() != null) {
            PdfPCell tel = text("TEL. " + g.getRcptPhone(), l.body, Element.ALIGN_LEFT, Rectangle.NO_BORDER);
            tel.setColspan(3);
            t.addCell(tel);
        }
        return t;
    }

    // ── 3. Piezas y SUBRUTA (CVA-01) ──
    private PdfPTable pieces(Guide g, Layout l) {
        PdfPTable t = new PdfPTable(new float[]{1.25f, 1f});
        t.setWidthPercentage(100);
        Phrase p = new Phrase();
        p.add(new Chunk(g.pieces() + "  ", l.pieceNum));
        Chunk word = new Chunk(piecesWord(g.pieces()), l.pieceTxt);
        word.setCharacterSpacing(4 * l.k);
        p.add(word);
        PdfPCell left = new PdfPCell(p);
        left.setBorder(Rectangle.BOTTOM);
        left.setHorizontalAlignment(Element.ALIGN_CENTER);
        left.setPaddingBottom(4 * l.k);
        t.addCell(left);
        Chunk sub = new Chunk(up(g.getSubRoute()), l.cd);
        sub.setCharacterSpacing(4 * l.k);
        PdfPCell right = new PdfPCell(new Phrase(sub));
        right.setBorder(Rectangle.BOTTOM);
        right.setHorizontalAlignment(Element.ALIGN_LEFT);
        right.setVerticalAlignment(Element.ALIGN_TOP);
        right.setPaddingTop(10 * l.k);
        t.addCell(right);
        return t;
    }

    // ── 4. Código PDF417 ──
    private PdfPTable barcode(Guide g, Layout l, float width) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c;
        try {
            BarcodePDF417 bc = new BarcodePDF417();
            bc.setText(GuideBarcode.encode(g, zone).getBytes(StandardCharsets.ISO_8859_1));
            bc.setErrorLevel(5);
            Image img = bc.getImage();
            img.scaleAbsolute(width * 0.9f, 88 * l.k);
            c = new PdfPCell(img, false);
        } catch (Exception e) {
            c = new PdfPCell(new Phrase(g.getFolio(), l.folio));
        }
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPaddingTop(9 * l.k);
        c.setPaddingBottom(9 * l.k);
        t.addCell(c);
        return t;
    }

    /** Contenido del PDF417 (ver {@link GuideBarcode}: versionado, sin datos personales, folio primero). */
    static String barcodeContent(Guide g, ZoneId zone) {
        return GuideBarcode.encode(g, zone);
    }

    static final String NOT_APPLICABLE = "NO APLICA";

    /** 1 → "PIEZA", otro → "PIEZAS". */
    static String piecesWord(int n) {
        return n == 1 ? "PIEZA" : "PIEZAS";
    }

    // ── 5. Folio, servicio y fecha compromiso ──
    private PdfPTable folioBox(Guide g, Layout l) {
        PdfPTable t = new PdfPTable(new float[]{1f, 1f});
        t.setWidthPercentage(100);
        Chunk f = new Chunk(printedFolio(g.getFolio()), l.folio);
        f.setCharacterSpacing(5 * l.k);
        PdfPCell folio = new PdfPCell(new Phrase(f));
        folio.setColspan(2);
        folio.setBorder(Rectangle.BOX);
        folio.setHorizontalAlignment(Element.ALIGN_CENTER);
        folio.setPaddingTop(3 * l.k);
        folio.setPaddingBottom(7 * l.k);
        t.addCell(folio);

        PdfPCell service = text("SERVICIO " + up(g.getServiceType()), l.service, Element.ALIGN_CENTER, Rectangle.LEFT | Rectangle.BOTTOM);
        service.setVerticalAlignment(Element.ALIGN_MIDDLE);
        service.setFixedHeight(48 * l.k);
        service.setPaddingTop(10 * l.k);
        t.addCell(service);

        PdfPCell commit = new PdfPCell();
        commit.setBorder(Rectangle.RIGHT | Rectangle.BOTTOM);
        Paragraph a = new Paragraph("FECHA COMPROMISO", l.service);
        a.setAlignment(Element.ALIGN_CENTER);
        commit.addElement(a);
        Paragraph b = new Paragraph(g.getCommitmentDate() == null ? "" : SHORT.format(g.getCommitmentDate()), l.service);
        b.setAlignment(Element.ALIGN_CENTER);
        b.setSpacingBefore(7 * l.k);
        commit.addElement(b);
        t.addCell(commit);
        return t;
    }

    // ── 6. Nombre y firma de quien recibe ──
    private PdfPTable signatures(Layout l) {
        PdfPTable t = new PdfPTable(new float[]{1f, 1f});
        t.setWidthPercentage(100);
        for (String s : new String[]{"NOMBRE", "FIRMA"}) {
            Chunk c = new Chunk(s, l.sign);
            c.setCharacterSpacing(4 * l.k);
            PdfPCell cell = new PdfPCell(new Phrase(c));
            cell.setBorder(Rectangle.TOP);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPaddingTop(2);
            t.addCell(cell);
        }
        PdfPTable wrap = new PdfPTable(1);
        wrap.setWidthPercentage(100);
        PdfPCell space = new PdfPCell();
        space.setBorder(Rectangle.NO_BORDER);
        space.setFixedHeight(34 * l.k);
        wrap.addCell(space);
        PdfPCell inner = new PdfPCell(t);
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(0);
        wrap.addCell(inner);
        return wrap;
    }

    // ── utilidades ──

    /** ATM000000001 → "ATM  000000001" (las siglas separadas del consecutivo, como en el boceto). */
    static String printedFolio(String folio) {
        if (folio == null) return "";
        Matcher m = java.util.regex.Pattern.compile("^([A-Z]+)-?(\\d+)$").matcher(folio);
        return m.matches() ? m.group(1) + "  " + m.group(2) : folio;
    }

    private static PdfPCell plain(PdfPTable inner) {
        PdfPCell c = new PdfPCell(inner);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell text(String value, Font font, int align, int border) {
        PdfPCell c = new PdfPCell(new Phrase(value == null ? "" : value, font));
        c.setBorder(border);
        c.setHorizontalAlignment(align);
        c.setPadding(1.5f);
        return c;
    }

    private static String spaced(String s) {
        return String.join(" ", s.split(""));
    }

    private static String code(String c) {
        return c == null ? "" : c.toUpperCase(Locale.ROOT);
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase(Locale.forLanguageTag("es-MX"));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String ascii(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace("|", "/");
    }

    private static String weight(BigDecimal kg) {
        return kg == null ? "" : kg.stripTrailingZeros().toPlainString() + " KG";
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
