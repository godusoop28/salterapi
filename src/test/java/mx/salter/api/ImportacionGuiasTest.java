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
        return excel(rows);
    }

    private static byte[] excel(String[][] rows) throws Exception {
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

    /** Archivos reales: nombre escrito distinto en el listado (mismo C.P.) y subruta de clientes nuevos por municipio. */
    @Test
    void relacionaPorCodigoPostalYSugiereSubrutaPorMunicipio() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente municipios");
        String remi = user(admin, "REMITENTE", sender, null);
        Object[][] catalog = {
            {"JIMENEZ AMAYA MARGARITOREFACCIONARIA JIMENEZ", "AV DOMINGO DIEZ 505", "Cuernavaca", "62250", "CVA-01"},
            {"TAPIA SANCHEZ RAFAEL", "LAZARO CARDENAS 129", "Jiutepec", "62577", "CVA-04"},
            {"SALGADO PASTRANA DAVID", "CARR FED CUAUTLA KM 16", "Jiutepec", "62575", "CVA-04"},
            {"CONSTANTINO JUAREZ JACOBO", "AV DOMINGO DIEZ 509", "Cuernavaca", "62250", "CVA-02"},
        };
        for (Object[] c : catalog) {
            assertThat(call(HttpMethod.POST, "/api/recipients", remi, Map.of("firstName", c[0], "route", "74", "subRoute", c[4],
                    "address", Map.of("street", c[1], "city", c[2], "state", "Morelos", "zip", c[3]))).status()).isEqualTo(201);
        }
        String[][] rows = {
            {"Nombre de cliente", "Pedido", "Dirección", "LPN", "Tipo LPN", "Carga", "Ruta"},
            {"MARGARITO JIMENEZ AMAYA", "8833645", "AV DOMINGO DIEZ, 505, Del Empleado, Cuernavaca, Del Empleado, Cuernavaca, Mexico, Morelos, 62250", "L-1", "Caja", "CARGA-M", "74"},
            {"ERICK EMANUEL BENITEZ ALANIS", "8833622", "EMILIANO ZAPATA, MZ 22, Morelos, Jiutepec, Morelos, Jiutepec, Mexico, Morelos, 62577", "L-2", "Caja", "CARGA-M", "74"},
            {"CLIENTE CUERNAVACA NUEVO", "8833700", "CALLE 1, 2, Centro, Cuernavaca, , Mexico, Morelos, 62000", "L-3", "Caja", "CARGA-M", "74"},
        };
        JsonNode groups = preview(remi, excel(rows)).get("groups");

        JsonNode margarito = find(groups, "MARGARITO JIMENEZ AMAYA");
        assertThat(margarito.get("matchType").asText()).isEqualTo("PROBABLE");
        assertThat(margarito.get("recipient").get("subRoute").asText()).isEqualTo("CVA-01");

        JsonNode erick = find(groups, "ERICK EMANUEL BENITEZ ALANIS");
        assertThat(erick.hasNonNull("recipient")).isFalse();
        assertThat(erick.get("suggestedSubRoute").asText()).isEqualTo("CVA-04");     // todos los de Jiutepec, ruta 74

        JsonNode cva = find(groups, "CLIENTE CUERNAVACA NUEVO");
        assertThat(cva.hasNonNull("suggestedSubRoute")).isFalse();                  // Cuernavaca tiene CVA-01 y CVA-02: no se adivina
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

        // Otro usuario del mismo remitente sube el mismo archivo: lo detecta por número de pedido.
        String otro = user(admin, "REMITENTE", sender, null);
        JsonNode dup = find(preview(otro, reporte()).get("groups"), "PEREZ LOPEZ ANA");
        assertThat(dup.get("existingFolio").asText()).isEqualTo(g.body().get("folio").asText());
        assertThat(texts(dup.get("duplicateOrders"))).containsExactly("9001", "9003");
        assertThat(find(preview(otro, reporte()).get("groups"), "CLIENTE NUEVO PRUEBA").hasNonNull("existingFolio")).isFalse();

        // Captura manual con un pedido ya usado: se rechaza indicando la guía, salvo que se pida explícitamente.
        Map<String, Object> manual = new java.util.HashMap<>(body);
        manual.put("clientRequestId", java.util.UUID.randomUUID().toString());
        manual.put("orderReference", "9003");
        Res rejected = call(HttpMethod.POST, "/api/guides", otro, manual);
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.body().get("code").asText()).isEqualTo("DUPLICATE_ORDER");
        assertThat(rejected.body().get("detail").asText()).contains("9003", g.body().get("folio").asText());
        manual.put("allowDuplicateOrder", true);
        assertThat(call(HttpMethod.POST, "/api/guides", otro, manual).status()).isEqualTo(201);
        // Un pedido distinto no se confunde con uno que lo contiene ("900" no es "9001").
        manual.put("clientRequestId", java.util.UUID.randomUUID().toString());
        manual.put("orderReference", "900");
        manual.put("allowDuplicateOrder", false);
        assertThat(call(HttpMethod.POST, "/api/guides", otro, manual).status()).isEqualTo(201);
    }

    /** Rutas de embarque del listado (V10): sin ruta predeterminada, la guía toma la de la RUTA del cliente. */
    @Test
    void asignaLaRutaDeEmbarqueSegunLaRutaDelCliente() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente con rutas del listado");
        String remi = user(admin, "REMITENTE", sender, null);
        JsonNode routes = call(HttpMethod.GET, "/api/shipping-routes", admin, null).body();
        List<String> codes = new ArrayList<>();
        routes.forEach(r -> codes.add(r.get("code").asText()));
        assertThat(codes).contains("74", "75", "76", "77", "78", "13", "19", "3");

        java.util.function.BiFunction<String, String, JsonNode> guideFor = (name, route) -> {
            try {
                long rid = call(HttpMethod.POST, "/api/recipients", remi, Map.of("firstName", name, "route", route, "subRoute", "CVA-03",
                        "address", Map.of("street", "Calle " + name, "city", "Jojutla"))).body().get("id").asLong();
                Res g = call(HttpMethod.POST, "/api/guides", remi, Map.of("clientRequestId", java.util.UUID.randomUUID().toString(),
                        "recipientId", rid, "items", List.of(Map.of("description", "Caja", "quantity", 1)), "confirm", false));
                assertThat(g.status()).as(String.valueOf(g.body())).isEqualTo(201);
                return g.body();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(guideFor.apply("CLIENTE RUTA SETENTA Y CUATRO", "74").get("shippingRoute").get("name").asText()).isEqualTo("74 · MORELOS");
        assertThat(guideFor.apply("CLIENTE RUTA CERO TRES", "03").get("shippingRoute").get("name").asText()).isEqualTo("3 · OAXACA ISTMO");
        assertThat(guideFor.apply("CLIENTE RUTA DESCONOCIDA", "99").hasNonNull("shippingRoute")).isFalse();
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
