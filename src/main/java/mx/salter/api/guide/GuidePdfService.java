package mx.salter.api.guide;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BarcodePDF417;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * Guía con el FORMATO OFICIAL del cliente. Se dibuja en posiciones absolutas tomadas de la muestra aprobada
 * (imagen de 1536×1024 px, proporción 3:2), con Montserrat incrustada, para que tamaños y distribución sean idénticos:
 * <pre>
 *  PBA → OAX                    │        [logo]
 *  AUTO TODO MEXICANA           │
 *  ─────────────────────────────────────────────
 *  C O N S I G N A T A R I O .
 *  NOMBRE                       │  FECHA         22/11/26
 *  CALLE                        │  PESO          NO APLICA
 *  CIUDAD ESTADO                │  DIMENSIONES   NO APLICA
 *        8 PIEZAS          │        CVA-01
 *  ─────────────────────────────────────────────
 *            [ código PDF417 ]
 *  ┌──────────── A T M   0 0 0 0 0 0 0 0 1 ────────┐
 *  │ SERVICIO ORDINARIO   │   FECHA COMPROMISO       │
 *  │                      │       23/11/26           │
 *  └───────────────────────────────────────────────┘
 *  ─────────────────────────────────────────────
 *       N O M B R E                    F I R M A
 * </pre>
 * Tamaño (pdf.guide.pageSize: LETTER o LABEL_4X6, o el parámetro size) y copias (pdf.guide.copies) configurables.
 * En carta caben dos guías por hoja; la etiqueta 4×6 va en horizontal, una guía por etiqueta.
 *
 * <p>Dos presentaciones con la MISMA lógica de generación:
 * <ul>
 *   <li>{@link Variant#CLIENTE}: el formato oficial (consignatario, nombre y firma), que es lo que se pega en el bulto.
 *       NO incluye el teléfono de quien recibe. Los datos personales no van en metadatos, nombre de archivo ni código.</li>
 *   <li>{@link Variant#INTERNA}: igual, más el teléfono de quien recibe, sólo para personal autorizado.</li>
 * </ul>
 */
@Service
public class GuidePdfService {
    public record Rendered(String filename, byte[] bytes) {}

    public enum Variant { CLIENTE, INTERNA }

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.forLanguageTag("es-MX"));
    /** Ancho del formato en puntos sobre hoja carta (19 cm; caben 2 guías por hoja). */
    private static final float LETTER_WIDTH = 540f;
    /** Medidas de la muestra: todas las coordenadas del dibujo están en px de ella (origen arriba a la izquierda). */
    static final float W = 1536f, H = 1024f;
    private static final Color ARROW = new Color(0x86, 0xBF, 0xF2);
    private static final float LINE = 3.5f;

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
        Rectangle page = label ? new Rectangle(432, 288) : PdfSupport.pageSize(size);
        float margin = label ? 8 : 30;
        float gap = 12;
        float s = label ? Math.min((page.getWidth() - 2 * margin) / W, (page.getHeight() - 2 * margin) / H)
                : Math.min(LETTER_WIDTH, page.getWidth() - 2 * margin) / W;
        float boxW = W * s, boxH = H * s;
        float x0 = (page.getWidth() - boxW) / 2;
        float firstTop = label ? (page.getHeight() + boxH) / 2 : page.getHeight() - margin;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(page, margin, margin, margin, margin);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        doc.addTitle("Guía " + printedFolio(g.getFolio()));
        doc.addCreator("SALTER");
        doc.open();
        Fonts f = Fonts.load();
        float top = firstTop;
        for (int c = 0; c < copies; c++) {
            if (c > 0) {
                top -= boxH + gap;
                if (label || top - boxH < margin) {
                    doc.newPage();
                    top = firstTop;
                }
            }
            // Algo de contenido de flujo para que la página exista aunque todo se dibuje en absoluto.
            writer.setPageEmpty(false);
            new Sheet(writer.getDirectContent(), f, x0, top, s).draw(g, v, zone);
        }
        doc.close();
        audit.record("GUIDE_PDF", "GUIDE", g.getId(), Map.of("folio", g.getFolio(), "variant", v.name(), "size", size.toUpperCase()));
        return new Rendered("guia-" + g.getFolio() + (v == Variant.CLIENTE ? "-cliente" : "-interna") + ".pdf", out.toByteArray());
    }

    /** Montserrat incrustada (como en la muestra); si no se puede cargar, Helvetica. */
    private record Fonts(BaseFont medium, BaseFont semi, BaseFont bold) {
        private static volatile Fonts cached;

        static Fonts load() {
            Fonts f = cached;
            if (f == null) {
                f = new Fonts(font("Montserrat-Medium", BaseFont.HELVETICA), font("Montserrat-SemiBold", BaseFont.HELVETICA_BOLD),
                        font("Montserrat-Bold", BaseFont.HELVETICA_BOLD));
                cached = f;
            }
            return f;
        }

        private static BaseFont font(String name, String fallback) {
            try (var in = GuidePdfService.class.getResourceAsStream("/fonts/" + name + ".ttf")) {
                if (in != null) {
                    return BaseFont.createFont(name + ".ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
                }
            } catch (Exception ignored) {
                // se usa la fuente de respaldo
            }
            try {
                return BaseFont.createFont(fallback, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Dibuja una guía en posiciones absolutas, escalada por {@code s} (pt por px de la muestra). */
    private static final class Sheet {
        final PdfContentByte cb;
        final Fonts f;
        final float x0, top, s;

        Sheet(PdfContentByte cb, Fonts f, float x0, float top, float s) {
            this.cb = cb;
            this.f = f;
            this.x0 = x0;
            this.top = top;
            this.s = s;
        }

        float X(float px) {
            return x0 + px * s;
        }

        float Y(float py) {
            return top - py * s;
        }

        void draw(Guide g, Variant v, ZoneId zone) {
            // ── 1. Ruta, remitente y logo ──
            String o = code(g.getOriginCode()), d = code(g.getDestinationCode());
            float routeSize = 78, arrowW = 86, arrowGap = 40;
            float groupW = width(f.bold, routeSize, 0, o) + 2 * arrowGap + arrowW + width(f.bold, routeSize, 0, d);
            float k = Math.min(1f, (830 - 105) / groupW);
            float rs = routeSize * k;
            float x = 105;
            text(f.bold, rs, 0, o, x, 85, Element.ALIGN_LEFT, 0);
            x += width(f.bold, rs, 0, o) + arrowGap * k;
            arrow(x, 55, arrowW * k, k);
            x += (arrowW + arrowGap) * k;
            text(f.bold, rs, 0, d, x, 85, Element.ALIGN_LEFT, 0);
            // Sólo el nombre del remitente (ej. AUTO TODO MEXICANA).
            text(f.medium, 30.5f, 0, up(g.getSenderName()), 105, 130, Element.ALIGN_LEFT, 830 - 105);
            vline(852, 12, 165);
            logo(1040, 6, 1300, 160);
            hline(171, 92, 1442);

            // ── 2. Consignatario (teléfono sólo en la versión interna) + fecha, peso, dimensiones ──
            spread(f.semi, 31, "CONSIGNATARIO.", 100, 217, 383);
            String line1 = up(g.getRcptClientCode() == null ? g.getRcptName() : "#" + g.getRcptClientCode() + "  " + g.getRcptName());
            String line2 = up(Text.join(g.getRcptStreet(), g.getRcptNeighborhood() == null ? null : "COL. " + g.getRcptNeighborhood()));
            String line3 = up(Text.join(g.getRcptCity(), g.getRcptState(), g.getRcptZip() == null ? null : "C.P. " + g.getRcptZip()));
            boolean phone = v == Variant.INTERNA && g.getRcptPhone() != null;
            String[] lines = phone ? new String[]{line1, line2, line3, "TEL. " + g.getRcptPhone()} : new String[]{line1, line2, line3};
            float[] base = phone ? new float[]{262, 295, 328, 361} : new float[]{273, 316, 358};
            float bodySize = phone ? 31 : 35.5f;
            for (int i = 0; i < lines.length; i++) text(f.semi, bodySize, 0, lines[i], 103, base[i], Element.ALIGN_LEFT, 840 - 103);
            vline(858, 228, 352);
            LocalDate date = g.getConfirmedAt() == null ? null : g.getConfirmedAt().atZone(zone).toLocalDate();
            String[][] rows = {
                    {"FECHA", date == null ? "" : SHORT.format(date)},
                    {"PESO", g.isWeightNotApplicable() ? NOT_APPLICABLE : weight(g.getTotalWeightKg())},
                    {"DIMENSIONES", g.isDimensionsNotApplicable() ? NOT_APPLICABLE : up(g.getDimensions())},
            };
            float[] rowBase = {242, 291, 340};
            for (int i = 0; i < rows.length; i++) {
                text(f.semi, 34, 0, rows[i][0], 1040, rowBase[i], Element.ALIGN_CENTER, 300);
                text(f.semi, 34, 0, rows[i][1], 1320, rowBase[i], Element.ALIGN_CENTER, 1440 - 1200);
            }

            // ── 3. Piezas y SUBRUTA (CVA-01) ──
            String n = String.valueOf(g.pieces()), word = piecesWord(g.pieces());
            float numSize = 100, wordSize = 69, wordSp = 4, between = 40;
            float pw = width(f.bold, numSize, 0, n) + between + width(f.bold, wordSize, wordSp, word);
            float pk = Math.min(1f, 660 / pw);
            float px = 421 - pw * pk / 2;
            text(f.bold, numSize * pk, 0, n, px, 458, Element.ALIGN_LEFT, 0);
            px += width(f.bold, numSize * pk, 0, n) + between * pk;
            text(f.bold, wordSize * pk, wordSp * pk, word, px, 456, Element.ALIGN_LEFT, 0);
            vline(781, 395, 462);
            text(f.bold, 92, 3, up(g.getSubRoute()), 1106, 456, Element.ALIGN_CENTER, 1430 - 800);
            hline(481, 92, 1444);

            // ── 4. Código PDF417 ──
            barcode(g, zone, 195, 507, 1325, 693);

            // ── 5. Folio, servicio y fecha compromiso ──
            cb.setLineWidth(LINE * s);
            cb.rectangle(X(95), Y(908), (1440 - 95) * s, (908 - 714) * s);
            cb.stroke();
            hline(796, 95, 1440);
            text(f.bold, 63, 10, printedFolio(g.getFolio()), 767, 773, Element.ALIGN_CENTER, 1300);
            text(f.semi, 40, 0, "SERVICIO " + up(g.getServiceType()), 147, 862, Element.ALIGN_LEFT, 750 - 147);
            vline(768, 810, 892);
            text(f.semi, 33, 0, "FECHA COMPROMISO", 1108, 833, Element.ALIGN_CENTER, 640);
            text(f.bold, 58, 0, g.getCommitmentDate() == null ? "" : SHORT.format(g.getCommitmentDate()), 1117, 885,
                    Element.ALIGN_CENTER, 640);

            // ── 6. Nombre y firma de quien recibe ──
            hline(952, 92, 1444);
            spread(f.semi, 28, "NOMBRE", 247, 987, 196);
            spread(f.semi, 28, "FIRMA", 1087, 987, 151);
        }

        float width(BaseFont bf, float sizePx, float spacingPx, String t) {
            if (t == null || t.isEmpty()) return 0;
            return bf.getWidthPoint(t, sizePx) + spacingPx * (t.length() - 1);
        }

        /** Texto en px de la muestra; si excede {@code maxPx} (&gt; 0) se reduce el tamaño hasta caber. */
        void text(BaseFont bf, float sizePx, float spacingPx, String t, float xPx, float basePx, int align, float maxPx) {
            if (t == null || t.isEmpty()) return;
            float w = width(bf, sizePx, spacingPx, t);
            if (maxPx > 0 && w > maxPx) {
                float r = maxPx / w;
                sizePx *= r;
                spacingPx *= r;
                w = maxPx;
            }
            float left = align == Element.ALIGN_CENTER ? xPx - w / 2 : align == Element.ALIGN_RIGHT ? xPx - w : xPx;
            cb.beginText();
            cb.setFontAndSize(bf, sizePx * s);
            cb.setCharacterSpacing(spacingPx * s);
            cb.setTextMatrix(X(left), Y(basePx));
            cb.showText(t);
            cb.setCharacterSpacing(0);
            cb.endText();
        }

        /** Texto con el espaciado entre letras necesario para ocupar {@code widthPx} (C O N S I G N A T A R I O). */
        void spread(BaseFont bf, float sizePx, String t, float xPx, float basePx, float widthPx) {
            float sp = Math.max(0, (widthPx - width(bf, sizePx, 0, t)) / (t.length() - 1));
            text(bf, sizePx, sp, t, xPx, basePx, Element.ALIGN_LEFT, 0);
        }

        void hline(float y, float x1, float x2) {
            cb.setLineWidth(LINE * s);
            cb.moveTo(X(x1), Y(y));
            cb.lineTo(X(x2), Y(y));
            cb.stroke();
        }

        void vline(float x, float y1, float y2) {
            cb.setLineWidth(LINE * s);
            cb.moveTo(X(x), Y(y1));
            cb.lineTo(X(x), Y(y2));
            cb.stroke();
        }

        /** Flecha azul claro de trazo redondeado entre origen y destino. */
        void arrow(float xPx, float yPx, float wPx, float k) {
            float head = 21 * k;
            cb.saveState();
            cb.setColorStroke(ARROW);
            cb.setLineWidth(9 * k * s);
            cb.setLineCap(PdfContentByte.LINE_CAP_ROUND);
            cb.setLineJoin(PdfContentByte.LINE_JOIN_ROUND);
            cb.moveTo(X(xPx), Y(yPx));
            cb.lineTo(X(xPx + wPx), Y(yPx));
            cb.moveTo(X(xPx + wPx - head), Y(yPx - head));
            cb.lineTo(X(xPx + wPx), Y(yPx));
            cb.lineTo(X(xPx + wPx - head), Y(yPx + head));
            cb.stroke();
            cb.restoreState();
        }

        void logo(float x1, float y1, float x2, float y2) {
            Image img = logoImage();
            if (img == null) {
                text(f.bold, 70, 0, "SALTER", (x1 + x2) / 2, (y1 + y2) / 2 + 25, Element.ALIGN_CENTER, x2 - x1);
                return;
            }
            try {
                img.scaleToFit((x2 - x1) * s, (y2 - y1) * s);
                float cx = X((x1 + x2) / 2), cy = Y((y1 + y2) / 2);
                img.setAbsolutePosition(cx - img.getScaledWidth() / 2, cy - img.getScaledHeight() / 2);
                cb.addImage(img);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void barcode(Guide g, ZoneId zone, float x1, float y1, float x2, float y2) {
            try {
                BarcodePDF417 bc = new BarcodePDF417();
                bc.setText(GuideBarcode.encode(g, zone).getBytes(StandardCharsets.ISO_8859_1));
                bc.setErrorLevel(5);
                Image img = bc.getImage();
                img.scaleAbsolute((x2 - x1) * s, (y2 - y1) * s);
                img.setAbsolutePosition(X(x1), Y(y2));
                cb.addImage(img);
            } catch (Exception e) {
                text(f.bold, 64, 0, g.getFolio(), (x1 + x2) / 2, (y1 + y2) / 2 + 22, Element.ALIGN_CENTER, x2 - x1);
            }
        }
    }

    private static Image logoImage() {
        try (var in = GuidePdfService.class.getResourceAsStream("/branding/salter-guia.png")) {
            if (in == null) return PdfSupport.logo();
            return Image.getInstance(in.readAllBytes());
        } catch (Exception e) {
            return PdfSupport.logo();
        }
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

    // ── utilidades ──

    /** ATM000000001 → "ATM  000000001" (las siglas separadas del consecutivo, como en el boceto). */
    static String printedFolio(String folio) {
        if (folio == null) return "";
        Matcher m = java.util.regex.Pattern.compile("^([A-Z]+)-?(\\d+)$").matcher(folio);
        return m.matches() ? m.group(1) + "  " + m.group(2) : folio;
    }

    private static String code(String c) {
        return c == null ? "" : c.toUpperCase(Locale.ROOT);
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase(Locale.forLanguageTag("es-MX"));
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
