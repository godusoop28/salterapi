package mx.salter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.pdf417.PDF417Reader;
import com.lowagie.text.pdf.BarcodePDF417;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideBarcode;
import mx.salter.api.report.ReportService;
import org.junit.jupiter.api.Test;

/** Código PDF417 de la guía: contenido versionado, sin datos personales, legible por un lector real (zxing). */
class CodigoBarrasTest {
    private static final ZoneId MX = ZoneId.of("America/Mexico_City");

    private static Guide guide() {
        Guide g = new Guide();
        g.setFolio("ATM000000123");
        g.setOriginCode("PBA");
        g.setDestinationCode("OAX");
        g.setPackageCount(8);
        g.setTotalWeightKg(new BigDecimal("10.50"));
        g.setConfirmedAt(Instant.parse("2026-11-13T18:00:00Z"));
        g.setSubRoute("CVA-01");
        g.setServiceType("ORDINARIO");
        g.setCommitmentDate(LocalDate.of(2026, 11, 14));
        g.setRcptName("Jaime Arturo Avelar Morales");
        g.setRcptPhone("7771234567");
        g.setRcptStreet("Nicolas Bravo 204");
        return g;
    }

    @Test
    void codificaYDecodificaLaVersion2SinDatosPersonales() {
        Guide g = guide();
        String code = GuideBarcode.encode(g, MX);
        assertThat(code).isEqualTo("ATM000000123|V2|PBA|OAX|8|10.5|20261113|CVA-01|ORDINARIO|20261114");
        assertThat(code).doesNotContain("Jaime", "Avelar", "7771234567", "Bravo");
        GuideBarcode.Payload p = GuideBarcode.decode(code);
        assertThat(p.version()).isEqualTo("V2");
        assertThat(p.folio()).isEqualTo("ATM000000123");
        assertThat(p.pieces()).isEqualTo(8);
        assertThat(p.subRoute()).isEqualTo("CVA-01");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(p.commitmentDate()).isEqualTo(LocalDate.of(2026, 11, 14));

        g.setWeightNotApplicable(true);
        assertThat(GuideBarcode.decode(GuideBarcode.encode(g, MX)).weight()).isEqualTo(GuideBarcode.NOT_APPLICABLE);
    }

    @Test
    void entiendeElFormatoAnterior() {
        GuideBarcode.Payload p = GuideBarcode.decode("ATM000000001|PBA|OAX|8|10|20261111|CVA-01");
        assertThat(p.version()).isEqualTo("V1");
        assertThat(p.folio()).isEqualTo("ATM000000001");
        assertThat(p.destinationCode()).isEqualTo("OAX");
        assertThat(p.pieces()).isEqualTo(8);
    }

    @Test
    void elPdf417GeneradoSeLeeConUnLectorReal() throws Exception {
        String code = GuideBarcode.encode(guide(), MX);
        BarcodePDF417 bc = new BarcodePDF417();
        bc.setText(code.getBytes(StandardCharsets.ISO_8859_1));
        bc.setErrorLevel(5); // igual que en GuidePdfService
        java.awt.Image awt = bc.createAwtImage(Color.BLACK, Color.WHITE);
        // Misma proporción con la que se imprime en hoja carta (360 × 88 pt), a ~4 px por punto.
        int w = 1440, h = 352, margin = 60;
        BufferedImage img = new BufferedImage(w + 2 * margin, h + 2 * margin, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = img.createGraphics();
        gr.setColor(Color.WHITE);
        gr.fillRect(0, 0, img.getWidth(), img.getHeight());
        gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        gr.drawImage(awt, margin, margin, w, h, null);
        gr.dispose();
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(img.getWidth(), img.getHeight(), px)));
        Result r = new PDF417Reader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE));
        assertThat(r.getText()).isEqualTo(code);
        assertThat(GuideBarcode.decode(r.getText()).folio()).isEqualTo("ATM000000123");
    }

    @Test
    void porcentajesDelDiaSuman100() {
        assertThat(ReportService.percentages(1, 1, 1)).containsExactly(34, 33, 33);
        assertThat(ReportService.percentages(2, 5, 0)).containsExactly(29, 71, 0);
        assertThat(ReportService.percentages(0, 0, 0)).containsExactly(0, 0, 0);
        int[] p = ReportService.percentages(7, 13, 3);
        assertThat(p[0] + p[1] + p[2]).isEqualTo(100);
    }
}
