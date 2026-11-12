package mx.salter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

/** Guías desde el reporte de carga del cliente: una guía por cliente con sus pedidos y bultos. */
class ImportacionGuiasTest extends IntegrationTestBase {

    /** Reporte con el mismo acomodo que el del cliente: columnas desde la U, sin encabezado en la primera página. */
    private static byte[] reporte() throws Exception {
        String[][] rows = {
            {"PEREZ LOPEZ ANA", "9001", "AV UNO, 10, Centro, Jojutla, , Mexico, Morelos, 62900", "LPN-1", "Caja", "CARGA-7", "74"},
            {" ", " ", " ", "LPN-2", "Atado", "CARGA-7", " "},
            {"REFACCIONES DEL SUR SA", "9002", "CARR FEDERAL KM 3, SN, Santa Rosa,", "LPN-3", "Caja", "CARGA-7", "74"},
            {null, null, "Tlaltizapan, , Mexico, Morelos, 62772", null, null, null, null},
            {" ", null, null, null, null, null, null},
            {"Nombre de cliente", "Pedido", "Dirección", "LPN", "Tipo LPN", "Carga", "Ruta"},
            {" ", " ", " ", "LPN-4", "Caja", "CARGA-7", " "},
            {"PEREZ LOPEZ ANA", "9003", "AV UNO, 10, Centro, Jojutla, , Mexico, Morelos, 62900", "LPN-5", "Caja", "CARGA-7", "74"},
            {"CLIENTE NUEVO PRUEBA", "9004", "CALLE 5, 1, Norte, Temixco, , Mexico, Morelos, 62580", "LPN-6", "Caja", "CARGA-7", "74"},
            {" ", " ", "Total de bultos: 6", null, " ", " ", null},
        };
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Hoja1");
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i);
                for (int j = 0; j < rows[i].length; j++) if (rows[i][j] != null) r.createCell(20 + j).setCellValue(rows[i][j]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private JsonNode preview(String token, byte[] file) throws Exception {
        var res = mvc.perform(multipart("/api/guides/import/preview")
                .file(new MockMultipartFile("file", "74f.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))
                .header("Authorization", "Bearer " + token).header("X-Requested-With", "test")).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return json.readTree(res.getContentAsByteArray());
    }

    @Test
    void agrupaPorClienteYReconoceElCatalogo() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente reporte");
        String remi = user(admin, "REMITENTE", sender, null);
        // En el catálogo: uno con el nombre en otro orden y otro idéntico con subruta.
        call(HttpMethod.POST, "/api/recipients", remi, Map.of("firstName", "ANA", "lastName", "PEREZ LOPEZ", "subRoute", "CVA-03",
                "address", Map.of("street", "Av Uno 10", "city", "Jojutla")));
        call(HttpMethod.POST, "/api/recipients", remi, Map.of("firstName", "REFACCIONES DEL SUR SA", "subRoute", "CVA-04",
                "address", Map.of("street", "Carr Federal", "city", "Tlaltizapan")));

        JsonNode p = preview(remi, reporte());
        assertThat(p.get("load").asText()).isEqualTo("CARGA-7");
        assertThat(p.get("declaredPackages").asInt()).isEqualTo(6);
        assertThat(p.get("totalPackages").asInt()).isEqualTo(6);
        assertThat(p.get("defaultDestination").asText()).isEqualTo("CVA");
        JsonNode groups = p.get("groups");
        assertThat(groups).hasSize(3);

        JsonNode ana = find(groups, "PEREZ LOPEZ ANA");
        assertThat(ana.get("packages").asInt()).isEqualTo(3);          // dos bloques del mismo cliente en una guía
        assertThat(texts(ana.get("orders"))).containsExactly("9001", "9003");
        assertThat(ana.get("matchType").asText()).isEqualTo("WORDS");
        assertThat(ana.get("recipient").get("destinationCode").asText()).isEqualTo("CVA");
        assertThat(ana.get("types").toString()).contains("\"Caja\"", "\"Atado\"");

        JsonNode sur = find(groups, "REFACCIONES DEL SUR SA");
        assertThat(sur.get("matchType").asText()).isEqualTo("EXACT");
        assertThat(sur.get("packages").asInt()).isEqualTo(2);          // continúa tras el encabezado repetido
        assertThat(sur.get("addressText").asText()).contains("Tlaltizapan");  // dirección en dos filas
        assertThat(sur.get("address").get("zip").asText()).isEqualTo("62772");
        assertThat(sur.get("address").get("street").asText()).isEqualTo("CARR FEDERAL KM 3 SN");

        JsonNode nuevo = find(groups, "CLIENTE NUEVO PRUEBA");
        assertThat(nuevo.hasNonNull("recipient")).isFalse();
        assertThat(nuevo.get("address").get("city").asText()).isEqualTo("Temixco");

        // Generar la guía con el requestId propuesto; al volver a subir el archivo aparece como ya generada.
        Map<String, Object> body = Map.of("clientRequestId", ana.get("requestId").asText(), "recipientId", ana.get("recipient").get("id").asLong(),
                "orderReference", "9001, 9003", "packageCount", 3, "notes", ana.get("notes").asText(),
                "shipping", Map.of("destinationCode", "CVA"),
                "items", List.of(Map.of("description", "Caja", "quantity", 2), Map.of("description", "Atado", "quantity", 1)), "confirm", true);
        Res g = call(HttpMethod.POST, "/api/guides", remi, body);
        assertThat(g.status()).isEqualTo(201);
        JsonNode again = find(preview(remi, reporte()).get("groups"), "PEREZ LOPEZ ANA");
        assertThat(again.get("existingFolio").asText()).isEqualTo(g.body().get("folio").asText());
        assertThat(call(HttpMethod.POST, "/api/guides", remi, body).status()).isEqualTo(200); // no se duplica
    }

    @Test
    void rechazaArchivosSinElFormatoDelReporte() throws Exception {
        String admin = admin();
        String remi = user(admin, "REMITENTE", createSender(admin, "Remitente formato"), null);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("x").createRow(0).createCell(0).setCellValue("Hola");
            wb.write(out);
            var res = mvc.perform(multipart("/api/guides/import/preview")
                    .file(new MockMultipartFile("file", "otro.xlsx", "application/octet-stream", out.toByteArray()))
                    .header("Authorization", "Bearer " + remi).header("X-Requested-With", "test")).andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(400);
            assertThat(res.getContentAsString()).contains("UNKNOWN_FORMAT");
        }
    }

    private static JsonNode find(JsonNode groups, String name) {
        for (JsonNode g : groups) if (g.get("clientName").asText().equals(name)) return g;
        throw new AssertionError("No está el cliente " + name);
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
