package mx.salter.api.common;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import java.awt.Color;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Utilidades comunes para documentos PDF con identidad SALTER. */
public final class PdfSupport {
    private PdfSupport() {}

    public static final Color BLUE = new Color(0x0B, 0x5C, 0xD5);
    public static final Color ORANGE = new Color(0xE3, 0x5D, 0x00);
    public static final Color NAVY = new Color(0x01, 0x16, 0x38);
    public static final Color LIGHT = new Color(0xF1, 0xF5, 0xFB);

    public static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, NAVY);
    public static final Font H2 = new Font(Font.HELVETICA, 10, Font.BOLD, BLUE);
    public static final Font LABEL = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.DARK_GRAY);
    public static final Font TEXT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    public static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.DARK_GRAY);
    public static final Font MONO = new Font(Font.COURIER, 14, Font.BOLD, NAVY);
    public static final Font WARN = new Font(Font.HELVETICA, 8, Font.BOLD, ORANGE);
    public static final Font TH = new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE);

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-MX"));

    public static Rectangle pageSize(String name) {
        return switch (name == null ? "" : name.toUpperCase()) {
            case "A4" -> PageSize.A4;
            case "HALF_LETTER" -> new Rectangle(396, 612);
            case "LETTER_LANDSCAPE" -> PageSize.LETTER.rotate();
            default -> PageSize.LETTER;
        };
    }

    public static String fmt(Instant i, ZoneId zone) {
        return i == null ? "—" : DT.format(i.atZone(zone));
    }

    public static String v(Object o) {
        return o == null || o.toString().isBlank() ? "—" : o.toString();
    }

    public static Image logo() {
        try (InputStream in = PdfSupport.class.getResourceAsStream("/branding/salter-compacto.png")) {
            if (in == null) return null;
            Image img = Image.getInstance(in.readAllBytes());
            img.scaleToFit(110, 36);
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /** Encabezado con logo, título y aviso de plantilla provisional. */
    public static void header(Document doc, String title, String subtitle) {
        PdfPTable t = new PdfPTable(new float[]{1.2f, 2.8f});
        t.setWidthPercentage(100);
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        Image logo = logo();
        if (logo != null) logoCell.addElement(logo);
        else logoCell.addElement(new Phrase("SALTER", TITLE));
        t.addCell(logoCell);
        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph p1 = new Paragraph(title, TITLE);
        p1.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(p1);
        if (subtitle != null) {
            Paragraph p2 = new Paragraph(subtitle, SMALL);
            p2.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(p2);
        }
        Paragraph warn = new Paragraph("PLANTILLA PROVISIONAL · formato oficial pendiente de validación", WARN);
        warn.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(warn);
        t.addCell(right);
        doc.add(t);
        Paragraph line = new Paragraph(new Chunk(" "));
        line.setSpacingAfter(4);
        doc.add(line);
    }

    public static PdfPCell labeled(String label, String value) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(new Color(0xD5, 0xDE, 0xEA));
        c.setPadding(5);
        c.addElement(new Paragraph(label.toUpperCase(), LABEL));
        c.addElement(new Paragraph(v(value), TEXT));
        return c;
    }

    public static PdfPCell th(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, TH));
        c.setBackgroundColor(BLUE);
        c.setPadding(4);
        c.setBorderColor(BLUE);
        return c;
    }

    public static PdfPCell td(String text) {
        PdfPCell c = new PdfPCell(new Phrase(v(text), TEXT));
        c.setPadding(4);
        c.setBorderColor(new Color(0xD5, 0xDE, 0xEA));
        return c;
    }

    public static Paragraph section(String text) {
        Paragraph p = new Paragraph(text, H2);
        p.setSpacingBefore(8);
        p.setSpacingAfter(4);
        return p;
    }
}
