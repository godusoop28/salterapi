package mx.salter.api.guide;

import static mx.salter.api.common.PdfSupport.td;
import static mx.salter.api.common.PdfSupport.th;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PdfSupport;
import mx.salter.api.sender.SenderService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * Resumen de las guías ESTIMADAS de una importación, antes de confirmarlas (pedido del cliente). Se genera con lo que el
 * usuario revisó en la tabla de la previsualización. No guarda nada: es sólo un documento para revisar o entregar.
 */
@Service
public class GuideImportSummaryService {
    public enum Format { PDF, XLSX }

    public record SummaryItem(@Size(max = 60) String type, @Min(0) @Max(9999) int quantity) {}

    public record SummaryRow(
            @Size(max = 40) String clientCode,
            @Size(max = 120) String clientName,
            @Size(max = 200) String orders,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 20) String route,
            @Size(max = 5) String destinationCode,
            @Size(max = 20) String subRoute,
            boolean newClient,
            @Size(max = 50) List<@Valid SummaryItem> items) {}

    public record SummaryRequest(
            Long senderId,
            @Size(max = 200) String fileName,
            @Size(max = 60) String load,
            @Size(max = 160) String shippingRoute,
            Integer declaredPackages,
            @Min(0) int excludedPieces,
            @NotEmpty @Size(max = 2000) List<@Valid SummaryRow> rows) {}

    public record Rendered(String filename, byte[] bytes) {}

    /** Términos del listado de clientes y del reporte de carga del remitente (# CLIENTE, MUNICIPIO, RUTA, SUB RUTA). */
    static final String[] HEADERS = {"#", "# Cliente", "Cliente", "Pedido(s)", "Municipio", "Estado", "Ruta", "Subruta", "Destino", "Bultos", "Piezas"};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-MX"));
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final SenderService senders;
    private final ZoneId zone;

    public GuideImportSummaryService(SenderService senders, ZoneId zone) {
        this.senders = senders;
        this.zone = zone;
    }

    public Rendered render(SummaryRequest req, Format format) {
        if (req.rows() == null || req.rows().isEmpty()) throw ApiException.badRequest("EMPTY_SUMMARY", "No hay guías seleccionadas.");
        String sender = senders.requireAccessible(req.senderId()).getName();
        Instant now = Instant.now();
        String base = "resumen-guias-" + FILE.format(now.atZone(zone));
        Totals t = totals(req);
        return format == Format.XLSX
                ? new Rendered(base + ".xlsx", xlsx(req, sender, now, t))
                : new Rendered(base + ".pdf", pdf(req, sender, now, t));
    }

    record Totals(int guides, int orders, int pieces) {}

    static Totals totals(SummaryRequest req) {
        int orders = 0, pieces = 0;
        for (SummaryRow r : req.rows()) {
            orders += orderCount(r.orders());
            pieces += pieces(r);
        }
        return new Totals(req.rows().size(), orders, pieces);
    }

    /** Mismo criterio que la pantalla: pedidos separados por coma/espacio, de al menos 3 caracteres. */
    static int orderCount(String orders) {
        if (orders == null) return 0;
        int n = 0;
        for (String o : orders.split("[^A-Za-z0-9-]+")) if (o.length() >= 3) n++;
        return n;
    }

    static int pieces(SummaryRow r) {
        return r.items() == null ? 0 : r.items().stream().mapToInt(SummaryItem::quantity).sum();
    }

    static String packages(SummaryRow r) {
        if (r.items() == null) return "";
        return r.items().stream().filter(i -> i.quantity() > 0)
                .map(i -> i.quantity() + " " + nz(i.type())).collect(Collectors.joining(", "));
    }

    private static String[] cells(int index, SummaryRow r) {
        return new String[]{String.valueOf(index), nz(r.clientCode()), nz(r.clientName()) + (r.newClient() ? " (nuevo)" : ""), nz(r.orders()),
                nz(r.city()), nz(r.state()), nz(r.route()), nz(r.subRoute()).toUpperCase(Locale.ROOT),
                nz(r.destinationCode()).toUpperCase(Locale.ROOT), packages(r), String.valueOf(pieces(r))};
    }

    private String declaredLine(SummaryRequest req, Totals t) {
        StringBuilder s = new StringBuilder();
        if (req.declaredPackages() != null) {
            s.append("Declaradas en el archivo: ").append(req.declaredPackages());
            int diff = req.declaredPackages() - t.pieces() - req.excludedPieces();
            if (diff != 0) s.append(" (diferencia ").append(diff).append(")");
        }
        if (req.excludedPieces() > 0) s.append(s.isEmpty() ? "" : " · ").append("Excluidas: ").append(req.excludedPieces());
        return s.toString();
    }

    private byte[] pdf(SummaryRequest req, String sender, Instant now, Totals t) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER.rotate(), 24, 24, 24, 24);
        PdfWriter.getInstance(doc, out);
        doc.addTitle("Resumen de guías por confirmar");
        doc.addCreator("SALTER");
        doc.open();
        PdfSupport.header(doc, "Resumen de guías por confirmar", "Estimado · " + STAMP.format(now.atZone(zone)));

        PdfPTable k = new PdfPTable(new float[]{2.2f, 1.6f, 1f, 2f, 0.8f, 0.8f, 0.8f});
        k.setWidthPercentage(100);
        k.addCell(PdfSupport.labeled("Remitente", sender));
        k.addCell(PdfSupport.labeled("Archivo", req.fileName()));
        k.addCell(PdfSupport.labeled("Carga", req.load()));
        k.addCell(PdfSupport.labeled("Ruta de embarque", req.shippingRoute() == null ? "Sin ruta (se asigna después)" : req.shippingRoute()));
        k.addCell(PdfSupport.labeled("Guías", String.valueOf(t.guides())));
        k.addCell(PdfSupport.labeled("Pedidos", String.valueOf(t.orders())));
        k.addCell(PdfSupport.labeled("Piezas", String.valueOf(t.pieces())));
        doc.add(k);
        String declared = declaredLine(req, t);
        if (!declared.isEmpty()) doc.add(new Paragraph(declared, PdfSupport.SMALL));

        doc.add(PdfSupport.section("Detalle (" + t.guides() + " guías)"));
        PdfPTable d = new PdfPTable(new float[]{0.35f, 0.7f, 2.5f, 1.2f, 1.3f, 1f, 0.5f, 0.8f, 0.65f, 1.8f, 0.6f});
        d.setWidthPercentage(100);
        d.setHeaderRows(1);
        for (String h : HEADERS) d.addCell(th(h));
        int i = 1;
        for (SummaryRow r : req.rows()) {
            String[] c = cells(i++, r);
            for (int j = 0; j < c.length; j++) {
                PdfPCell cell = td(c[j]);
                if (j == 0 || j == c.length - 1) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                d.addCell(cell);
            }
        }
        PdfPCell label = new PdfPCell(new Phrase("TOTAL", PdfSupport.LABEL));
        label.setColspan(HEADERS.length - 1);
        label.setHorizontalAlignment(Element.ALIGN_RIGHT);
        label.setPadding(4);
        d.addCell(label);
        PdfPCell total = new PdfPCell(new Phrase(String.valueOf(t.pieces()), PdfSupport.LABEL));
        total.setHorizontalAlignment(Element.ALIGN_RIGHT);
        total.setPadding(4);
        d.addCell(total);
        doc.add(d);

        Paragraph note = new Paragraph("Documento estimado: las guías aún no están confirmadas ni tienen folio. Los datos pueden cambiar al generarlas.",
                PdfSupport.SMALL);
        note.setSpacingBefore(6);
        doc.add(note);

        PdfPTable sign = new PdfPTable(new float[]{1f, 0.4f, 1f});
        sign.setWidthPercentage(70);
        sign.setSpacingBefore(40);
        for (String s : new String[]{"ELABORÓ", "", "REVISÓ"}) {
            PdfPCell c = new PdfPCell(new Phrase(s, PdfSupport.LABEL));
            c.setBorder(s.isEmpty() ? Rectangle.NO_BORDER : Rectangle.TOP);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setPaddingTop(3);
            sign.addCell(c);
        }
        doc.add(sign);
        doc.close();
        return out.toByteArray();
    }

    private byte[] xlsx(SummaryRequest req, String sender, Instant now, Totals t) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Resumen");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            int n = 0;
            Row title = sh.createRow(n++);
            Cell tc = title.createCell(0);
            tc.setCellValue("Resumen de guías por confirmar (estimado)");
            tc.setCellStyle(bold);
            String[][] meta = {
                    {"Generado", STAMP.format(now.atZone(zone))},
                    {"Remitente", sender},
                    {"Archivo", nz(req.fileName())},
                    {"Carga", nz(req.load())},
                    {"Ruta de embarque", req.shippingRoute() == null ? "Sin ruta (se asigna después)" : req.shippingRoute()},
                    {"Guías", String.valueOf(t.guides())},
                    {"Pedidos", String.valueOf(t.orders())},
                    {"Piezas", String.valueOf(t.pieces())},
            };
            for (String[] m : meta) {
                Row r = sh.createRow(n++);
                Cell a = r.createCell(0);
                a.setCellValue(m[0]);
                a.setCellStyle(bold);
                r.createCell(1).setCellValue(m[1]);
            }
            String declared = declaredLine(req, t);
            if (!declared.isEmpty()) sh.createRow(n++).createCell(0).setCellValue(declared);
            n++;
            Row h = sh.createRow(n++);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(bold);
            }
            int i = 1;
            for (SummaryRow r : req.rows()) {
                Row row = sh.createRow(n++);
                String[] c = cells(i, r);
                row.createCell(0).setCellValue(i++);
                for (int j = 1; j < c.length - 1; j++) row.createCell(j).setCellValue(c[j]);
                row.createCell(c.length - 1).setCellValue(pieces(r));
            }
            Row tot = sh.createRow(n);
            Cell tl = tot.createCell(HEADERS.length - 2);
            tl.setCellValue("TOTAL");
            tl.setCellStyle(bold);
            Cell tv = tot.createCell(HEADERS.length - 1);
            tv.setCellValue(t.pieces());
            tv.setCellStyle(bold);
            for (int c = 0; c < HEADERS.length; c++) sh.autoSizeColumn(c);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
