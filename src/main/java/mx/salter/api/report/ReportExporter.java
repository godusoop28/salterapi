package mx.salter.api.report;

import static mx.salter.api.common.PdfSupport.td;
import static mx.salter.api.common.PdfSupport.th;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import mx.salter.api.common.PdfSupport;
import mx.salter.api.guide.GuideStatus;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ReportExporter {
    static final String[] KEYS = {"folio", "status", "created_at", "confirmed_at", "sender_name", "rcpt_name", "rcpt_city",
            "rcpt_state", "cd_reference", "order_reference", "package_count", "shift", "created_by", "courier"};
    static final String[] HEADERS = {"Folio", "Estado", "Elaborada", "Confirmada", "Remitente", "Destinatario", "Ciudad",
            "Estado (entidad)", "Ref. CD", "Pedido", "Bultos", "Turno", "Elaboró", "Repartidor"};

    public byte[] csv(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", HEADERS)).append("\r\n");
        for (Map<String, Object> r : rows) {
            for (int i = 0; i < KEYS.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(escape(value(r, KEYS[i])));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] xlsx(List<Map<String, Object>> rows, String title) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Guías");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Row t = sh.createRow(0);
            t.createCell(0).setCellValue(title);
            Row h = sh.createRow(2);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
            }
            int n = 3;
            for (Map<String, Object> r : rows) {
                Row row = sh.createRow(n++);
                for (int i = 0; i < KEYS.length; i++) {
                    Object v = r.get(KEYS[i]);
                    if (v instanceof Number num) row.createCell(i).setCellValue(num.doubleValue());
                    else row.createCell(i).setCellValue(value(r, KEYS[i]));
                }
            }
            for (int i = 0; i < HEADERS.length; i++) sh.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] pdf(List<Map<String, Object>> rows, ReportService.Summary s, String title) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER.rotate(), 24, 24, 24, 24);
        PdfWriter.getInstance(doc, out);
        doc.open();
        PdfSupport.header(doc, "Reporte de guías", title);
        PdfPTable k = new PdfPTable(8);
        k.setWidthPercentage(100);
        k.addCell(PdfSupport.labeled("Elaboradas", String.valueOf(s.generated())));
        k.addCell(PdfSupport.labeled("Confirmadas", String.valueOf(s.confirmed())));
        k.addCell(PdfSupport.labeled("Listas p/ salida", String.valueOf(s.readyForDispatch())));
        k.addCell(PdfSupport.labeled("Despachadas", String.valueOf(s.dispatched())));
        k.addCell(PdfSupport.labeled("En tránsito", String.valueOf(s.inTransit())));
        k.addCell(PdfSupport.labeled("Recibidas", String.valueOf(s.received())));
        k.addCell(PdfSupport.labeled("Entregadas", String.valueOf(s.delivered())));
        k.addCell(PdfSupport.labeled("Incidencias", String.valueOf(s.incidentsCreated())));
        doc.add(k);
        doc.add(PdfSupport.section("Detalle (" + rows.size() + " guías)"));
        int[] cols = {0, 1, 2, 4, 5, 6, 8, 10, 11, 13};
        PdfPTable t = new PdfPTable(cols.length);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (int c : cols) t.addCell(th(HEADERS[c]));
        for (Map<String, Object> r : rows) for (int c : cols) t.addCell(td(value(r, KEYS[c])));
        doc.add(t);
        doc.add(new Paragraph("Generado por SALTER a partir de los movimientos registrados.", PdfSupport.SMALL));
        doc.close();
        return out.toByteArray();
    }

    private static String value(Map<String, Object> r, String key) {
        Object v = r.get(key);
        if (v == null) return "";
        if ("status".equals(key)) {
            try {
                return GuideStatus.valueOf(v.toString()).getLabel();
            } catch (IllegalArgumentException e) {
                return v.toString();
            }
        }
        return v.toString();
    }

    private static String escape(String s) {
        String v = s;
        // Evita inyección de fórmulas al abrir el CSV en Excel.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
