package mx.salter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Carrito de bultos, piezas, corrección sólo por administrador, formato de guía (cliente / interna), rutas de embarque,
 * agrupación automática en manifiestos, Viaja / No viaja, autorización de salida, importación masiva y gráfica del día.
 */
class RutasManifiestosGuiasTest extends IntegrationTestBase {

    private long route(String admin, String code) throws Exception {
        Res r = call(POST, "/api/shipping-routes", admin, Map.of("code", code, "name", "Ruta " + code));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private long senderWithRoute(String admin, String name, Long routeId) throws Exception {
        Map<String, Object> b = new HashMap<>(Map.of("name", name, "kind", "EMPRESA", "plazaCode", "PBA",
                "address", Map.of("city", "Puebla", "state", "Puebla")));
        if (routeId != null) b.put("defaultShippingRouteId", routeId);
        Res r = call(POST, "/api/senders", admin, b);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private static Map<String, Object> item(String type, int qty) {
        return Map.of("description", type, "quantity", qty);
    }

    private Map<String, Object> body(long sender, String name, List<Map<String, Object>> items, boolean confirm) {
        Map<String, Object> b = new HashMap<>();
        b.put("clientRequestId", UUID.randomUUID().toString());
        b.put("senderId", sender);
        b.put("newRecipient", Map.of("firstName", name, "address", Map.of("street", "Calle 1", "city", "Oaxaca"), "saveToCatalog", false));
        b.put("items", items);
        b.put("shipping", Map.of("destinationCode", "OAX"));
        b.put("confirm", confirm);
        return b;
    }

    private MockHttpServletResponse pdf(String token, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder sb = new StringBuilder();
        PdfTextExtractor ex = new PdfTextExtractor(reader);
        for (int i = 1; i <= reader.getNumberOfPages(); i++) sb.append(ex.getTextFromPage(i)).append('\n');
        return sb.toString();
    }

    @Test
    void resumenDeGuiasEstimadasAntesDeConfirmar() throws Exception {
        String admin = admin();
        long sender = senderWithRoute(admin, "Auto Todo Resumen", null);
        String remi = user(admin, "REMITENTE", sender, null);
        Map<String, Object> req = new HashMap<>();
        req.put("fileName", "carga.xlsx");
        req.put("load", "CARGA-77");
        req.put("shippingRoute", "74 · MORELOS");
        req.put("declaredPackages", 9);
        req.put("excludedPieces", 0);
        req.put("rows", List.of(
                Map.of("clientCode", "5788", "clientName", "Refaccionaria Uno", "orders", "1001, 1002", "city", "Jojutla", "state", "Morelos",
                        "route", "74", "destinationCode", "cva", "subRoute", "CVA-01", "newClient", false,
                        "items", List.of(Map.of("type", "Caja", "quantity", 4), Map.of("type", "Sobre", "quantity", 2))),
                Map.of("clientName", "Taller Dos", "orders", "2001", "city", "Cuautla", "state", "Morelos",
                        "destinationCode", "CVA", "subRoute", "", "newClient", true,
                        "items", List.of(Map.of("type", "Tambo", "quantity", 2)))));
        long before = call(GET, "/api/guides?size=1", admin, null).body().get("totalElements").asLong();

        MockHttpServletResponse pdf = mvc.perform(MockMvcRequestBuilders.post("/api/guides/import/summary")
                .header("Authorization", "Bearer " + remi).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(req))).andReturn().getResponse();
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        String t = text(pdf.getContentAsByteArray());
        assertThat(t).contains("Resumen de guías por confirmar", "Auto Todo Resumen", "CARGA-77",
                "74 · MORELOS", "Refaccionaria Uno", "1001, 1002", "4 Caja, 2 Sobre", "Taller Dos (nuevo)", "CVA", "TOTAL", "8", "# Cliente", "Municipio", "5788",
                "diferencia 1", "ELABORÓ", "REVISÓ");

        MockHttpServletResponse x = mvc.perform(MockMvcRequestBuilders.post("/api/guides/import/summary?format=XLSX")
                .header("Authorization", "Bearer " + remi).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(req))).andReturn().getResponse();
        assertThat(x.getStatus()).isEqualTo(200);
        assertThat(x.getHeader("Content-Disposition")).contains(".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(x.getContentAsByteArray()))) {
            Sheet sh = wb.getSheetAt(0);
            List<String> clients = new ArrayList<>();
            double total = -1;
            for (Row r : sh) {
                if (r.getCell(2) != null && r.getCell(0) != null && r.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC)
                    clients.add(r.getCell(2).getStringCellValue());
                if (r.getCell(9) != null && "TOTAL".equals(r.getCell(9).getStringCellValue())) total = r.getCell(10).getNumericCellValue();
            }
            assertThat(clients).containsExactly("Refaccionaria Uno", "Taller Dos (nuevo)");
            assertThat(total).isEqualTo(8);
        }
        java.nio.file.Files.write(java.nio.file.Path.of("target", "resumen-guias-ejemplo.pdf"), pdf.getContentAsByteArray());
        java.nio.file.Files.write(java.nio.file.Path.of("target", "resumen-guias-ejemplo.xlsx"), x.getContentAsByteArray());
        // No guarda nada
        assertThat(call(GET, "/api/guides?size=1", admin, null).body().get("totalElements").asLong()).isEqualTo(before);
        // Un remitente no puede sacar el resumen a nombre de otro
        long other = senderWithRoute(admin, "Otro Remitente", null);
        req.put("senderId", other);
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/api/guides/import/summary")
                .header("Authorization", "Bearer " + remi).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(req))).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void carritoDeBultosPiezasYConfirmacion() throws Exception {
        String admin = admin();
        long sender = senderWithRoute(admin, "Remitente carrito", null);
        String remi = user(admin, "REMITENTE", sender, null);

        // Caja 4 + Sobre 2 + Tambo 1 + Tarima 1 = 8 piezas en UNA guía
        List<Map<String, Object>> cart = List.of(item("Caja", 4), item("Sobre", 2), item("Tambo", 1), item("Tarima", 1));
        Res g = call(POST, "/api/guides", remi, body(sender, "Cliente carrito", cart, true));
        assertThat(g.status()).as(String.valueOf(g.body())).isEqualTo(201);
        assertThat(g.body().get("packageCount").asInt()).isEqualTo(8);
        assertThat(g.body().get("items")).hasSize(4);
        assertThat(g.body().get("folio").asText()).matches("^RC\\d{9}$");

        // Piezas declaradas que no coinciden con la suma de los bultos: se rechaza
        Map<String, Object> mismatch = body(sender, "Otro", cart, true);
        mismatch.put("packageCount", 7);
        Res bad = call(POST, "/api/guides", remi, mismatch);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().get("code").asText()).isEqualTo("PACKAGE_COUNT_MISMATCH");

        // Cantidades no enteras: rechazo
        Res frac = call(POST, "/api/guides", remi, body(sender, "Fraccion", List.of(Map.of("description", "Caja", "quantity", 1.5)), false));
        assertThat(frac.body().get("code").asText()).isEqualTo("ITEM_QUANTITY");

        // Borrador sin bultos: la cantidad queda VACÍA (no se asume 1) y no se puede confirmar
        Res draft = call(POST, "/api/guides", remi, body(sender, "Borrador vacio", List.of(), false));
        assertThat(draft.status()).as(String.valueOf(draft.body())).isEqualTo(201);
        assertThat(draft.body().has("packageCount")).isFalse();
        Res conf = call(POST, "/api/guides/" + draft.body().get("id").asLong() + "/confirm", remi, null);
        assertThat(conf.status()).isEqualTo(422);
        assertThat(conf.body().get("code").asText()).isEqualTo("INCOMPLETE_GUIDE");
        assertThat(conf.body().get("detail").asText()).contains("bultos");

        // Doble envío del mismo clientRequestId: misma guía
        Map<String, Object> once = body(sender, "Una vez", List.of(item("Caja", 1)), true);
        long first = call(POST, "/api/guides", remi, once).body().get("id").asLong();
        Res second = call(POST, "/api/guides", remi, once);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("id").asLong()).isEqualTo(first);
    }

    @Test
    void soloElAdministradorCorrigeGuiasConfirmadas() throws Exception {
        String admin = admin();
        long sender = senderWithRoute(admin, "Remitente correcciones", null);
        String remi = user(admin, "REMITENTE", sender, null);
        // Aun con el permiso concedido, un usuario que no es Administrador no corrige
        String ops = user(admin, "OPERACIONES", null, null, "GUIDES_CORRECT");
        JsonNode g = call(POST, "/api/guides", remi, body(sender, "Corregible", List.of(item("Caja", 2)), true)).body();
        long id = g.get("id").asLong();
        assertThat(g.get("allowedActions").toString()).doesNotContain("CORRECT");
        Map<String, Object> fix = Map.of("reason", "Pedido equivocado", "orderReference", "777", "version", g.get("version").asLong());
        assertThat(call(POST, "/api/guides/" + id + "/corrections", remi, fix).status()).isEqualTo(403);
        assertThat(call(POST, "/api/guides/" + id + "/corrections", ops, fix).status()).isEqualTo(403);
        Res ok = call(POST, "/api/guides/" + id + "/corrections", admin, fix);
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(200);
        assertThat(ok.body().get("orderReference").asText()).isEqualTo("777");
        JsonNode last = null;
        for (JsonNode m : call(GET, "/api/guides/" + id + "/movements", admin, null).body()) if (m.get("type").asText().equals("CORRECCION")) last = m;
        assertThat(last).isNotNull();
        assertThat(last.get("userName").asText()).isNotBlank();
        assertThat(last.get("notes").asText()).isEqualTo("Pedido equivocado");
        assertThat(last.get("metadata").get("pedido").get("despues").asText()).isEqualTo("777");
    }

    @Test
    void formatoDeGuiaClienteEInterna() throws Exception {
        String admin = admin();
        long sender = call(POST, "/api/senders", admin, Map.of("name", "Auto Todo Mexicana", "kind", "EMPRESA", "plazaCode", "PBA",
                "address", Map.of("city", "Puebla", "state", "Puebla"))).body().get("id").asLong();
        String remi = user(admin, "REMITENTE", sender, null);
        Map<String, Object> b = new HashMap<>();
        b.put("clientRequestId", UUID.randomUUID().toString());
        b.put("newRecipient", Map.of("firstName", "Jaime Arturo", "lastName", "Avelar Morales", "phone", "7775550000",
                "address", Map.of("street", "Nicolas Bravo 204", "city", "Jojutla", "state", "Morelos"), "saveToCatalog", false));
        b.put("items", List.of(item("Caja", 4), item("Sobre", 2), item("Tambo", 1), item("Tarima", 1)));
        b.put("subRoute", "cva 01");
        b.put("shipping", Map.of("destinationCode", "OAX", "weightNotApplicable", true, "dimensionsNotApplicable", true));
        b.put("confirm", true);
        JsonNode g = call(POST, "/api/guides", remi, b).body();
        assertThat(g.get("subRoute").asText()).isEqualTo("CVA-01");
        assertThat(g.has("cdReference")).isFalse(); // la subruta NO es la referencia CD
        assertThat(g.get("shipping").get("weightNotApplicable").asBoolean()).isTrue();
        long id = g.get("id").asLong();

        MockHttpServletResponse cli = pdf(remi, "/api/guides/" + id + "/pdf");
        assertThat(cli.getStatus()).isEqualTo(200);
        assertThat(cli.getHeader("Content-Disposition")).contains("-cliente.pdf").doesNotContain("Jaime", "Avelar");
        String t = text(cli.getContentAsByteArray());
        assertThat(t).contains("AUTO TODO MEXICANA", "CVA-01", "NO APLICA", "PIEZAS", "8");
        assertThat(t.toUpperCase()).doesNotContain("PUEBLA");
        // La etiqueta del cliente lleva el consignatario y la firma (formato oficial); sólo el teléfono queda fuera
        assertThat(t.toUpperCase()).contains("CONSIGNATARIO", "AVELAR MORALES", "NICOLAS BRAVO 204", "JOJUTLA", "MORELOS", "FIRMA");
        assertThat(t).doesNotContain("7775550000");

        // La versión interna (con teléfono) es sólo para personal operativo
        assertThat(pdf(remi, "/api/guides/" + id + "/pdf?variant=INTERNA").getStatus()).isEqualTo(403);
        MockHttpServletResponse internal = pdf(admin, "/api/guides/" + id + "/pdf?variant=INTERNA&size=LABEL_4X6");
        assertThat(internal.getStatus()).isEqualTo(200);
        String ti = text(internal.getContentAsByteArray()).toUpperCase();
        assertThat(ti).contains("AUTO TODO MEXICANA", "JAIME ARTURO", "CVA-01", "NO APLICA", "7775550000");
        assertThat(ti).doesNotContain("PUEBLA");
        java.nio.file.Files.write(java.nio.file.Path.of("target", "guia-cliente-ejemplo.pdf"), cli.getContentAsByteArray());
        java.nio.file.Files.write(java.nio.file.Path.of("target", "guia-interna-4x6-ejemplo.pdf"), internal.getContentAsByteArray());

        // Una pieza: singular
        JsonNode one = call(POST, "/api/guides", remi, body(sender, "Uno", List.of(item("Sobre", 1)), true)).body();
        String t1 = text(pdf(remi, "/api/guides/" + one.get("id").asLong() + "/pdf").getContentAsByteArray());
        assertThat(t1).contains("PIEZA").doesNotContain("PIEZAS");

        // Lectura del código: decodifica y localiza la guía por folio
        JsonNode scan = call(GET, "/api/guides/barcode?code=" + g.get("folio").asText() + "|V2|PBA|OAX|8|NA", admin, null).body();
        assertThat(scan.get("guideId").asLong()).isEqualTo(id);
        assertThat(scan.get("payload").get("weight").asText()).isEqualTo("NA");
    }

    @Test
    void manifiestosPorRutaViajaNoViajaYAutorizacion() throws Exception {
        String admin = admin();
        long r1 = route(admin, "OAX-1");
        long r2 = route(admin, "CVA-2");
        long sender = senderWithRoute(admin, "Remitente rutas", r1);
        String remi = user(admin, "REMITENTE", sender, null);
        String ops = user(admin, "OPERACIONES", null, null);
        String enc = user(admin, "ENCARGADO", null, null);

        // Al confirmar, cada guía entra sola al manifiesto EN PREPARACIÓN de la ruta predeterminada
        JsonNode g1 = call(POST, "/api/guides", remi, body(sender, "Uno", List.of(item("Caja", 3)), true)).body();
        JsonNode g2 = call(POST, "/api/guides", remi, body(sender, "Dos", List.of(item("Caja", 1), item("Sobre", 1)), true)).body();
        JsonNode g3 = call(POST, "/api/guides", remi, body(sender, "Tres", List.of(item("Tarima", 1)), true)).body();
        assertThat(g1.get("shippingRoute").get("id").asLong()).isEqualTo(r1);
        long m1 = g1.get("manifest").get("id").asLong();
        assertThat(g2.get("manifest").get("id").asLong()).isEqualTo(m1);
        assertThat(g1.get("manifest").get("travels").asBoolean()).isTrue();

        // El remitente no puede elegir otra ruta; un usuario autorizado sí, con motivo
        Map<String, Object> other = body(sender, "Otra ruta", List.of(item("Caja", 1)), true);
        other.put("shippingRouteId", r2);
        assertThat(call(POST, "/api/guides", remi, other).status()).isEqualTo(403);
        assertThat(call(POST, "/api/guides", admin, other).body().get("code").asText()).isEqualTo("ROUTE_REASON_REQUIRED");

        // Cambio de ruta de una guía confirmada: se mueve al manifiesto de la nueva ruta y queda en el historial
        Res moved = call(POST, "/api/guides/" + g3.get("id").asLong() + "/route", ops,
                Map.of("shippingRouteId", r2, "reason", "Cliente pidió entrega en CVA"));
        assertThat(moved.status()).as(String.valueOf(moved.body())).isEqualTo(200);
        long m2 = moved.body().get("manifest").get("id").asLong();
        assertThat(m2).isNotEqualTo(m1);
        assertThat(call(POST, "/api/guides/" + g3.get("id").asLong() + "/route", remi, Map.of("shippingRouteId", r1, "reason", "x")).status()).isEqualTo(403);
        JsonNode man2 = call(GET, "/api/manifests/" + m2, ops, null).body();
        assertThat(man2.get("shippingRoute").get("id").asLong()).isEqualTo(r2);
        assertThat(man2.get("review").get("routeChanges")).hasSize(1);
        assertThat(man2.get("review").get("routeChanges").get(0).get("reason").asText()).isEqualTo("Cliente pidió entrega en CVA");

        // Revisión: totales del manifiesto de la ruta 1
        JsonNode man = call(GET, "/api/manifests/" + m1, ops, null).body();
        assertThat(man.get("review").get("guides").asInt()).isEqualTo(2);
        assertThat(man.get("review").get("pieces").asInt()).isEqualTo(5);
        assertThat(man.get("review").get("boxes").asInt()).isEqualTo(4);

        // No viaja: motivo obligatorio; queda registrado quién lo pidió
        String travelPath = "/api/manifests/" + m1 + "/guides/" + g2.get("id").asLong() + "/travel";
        assertThat(call(POST, travelPath, ops, Map.of("travels", false)).status()).isEqualTo(400);
        man = call(POST, travelPath, ops, Map.of("travels", false, "reason", "No cupo en la unidad")).body();
        assertThat(man.get("review").get("notTravelling").asInt()).isEqualTo(1);
        assertThat(man.get("review").get("travellingPieces").asInt()).isEqualTo(3);
        JsonNode entry = null;
        for (JsonNode e : man.get("entries")) if (e.get("guideId").asLong() == g2.get("id").asLong()) entry = e;
        assertThat(entry.get("travels").asBoolean()).isFalse();
        assertThat(entry.get("travelChangedBy").get("name").asText()).isNotBlank();

        // Cerrar (confirmar), autorizar (encargado) y despachar (operaciones) son pasos distintos
        man = call(POST, "/api/manifests/" + m1 + "/close?version=" + man.get("version").asLong(), ops, null).body();
        assertThat(man.get("status").asText()).isEqualTo("CERRADO");
        long v = man.get("version").asLong();
        assertThat(call(POST, "/api/manifests/" + m1 + "/dispatch", ops, Map.of("version", v)).body().get("code").asText()).isEqualTo("NOT_AUTHORIZED");
        assertThat(call(POST, "/api/manifests/" + m1 + "/authorize", ops, Map.of("version", v)).status()).isEqualTo(403);
        man = call(POST, "/api/manifests/" + m1 + "/authorize", enc, Map.of("notes", "Unidad 12 revisada", "version", v)).body();
        assertThat(man.get("authorization").get("notes").asText()).isEqualTo("Unidad 12 revisada");
        v = man.get("version").asLong();
        assertThat(call(POST, "/api/manifests/" + m1 + "/dispatch", enc, Map.of("version", v)).status()).isEqualTo(403);
        Res disp = call(POST, "/api/manifests/" + m1 + "/dispatch", ops, Map.of("vehicle", "Unidad 12", "version", v));
        assertThat(disp.status()).as(String.valueOf(disp.body())).isEqualTo(200);
        assertThat(disp.body().get("dispatch").get("guideCount").asInt()).isEqualTo(1); // sólo la que viaja
        assertThat(call(POST, "/api/manifests/" + m1 + "/dispatch", ops, Map.of("version", disp.body().get("version").asLong())).status()).isEqualTo(409);

        // La que viaja sale; la que no viaja conserva su estado, queda en el historial y pasa al siguiente manifiesto de la ruta
        assertThat(call(GET, "/api/guides/" + g1.get("id").asLong(), admin, null).body().get("status").asText()).isEqualTo("EN_TRANSITO");
        JsonNode after = call(GET, "/api/guides/" + g2.get("id").asLong(), admin, null).body();
        assertThat(after.get("status").asText()).isEqualTo("CONFIRMADA");
        assertThat(after.get("manifest").get("id").asLong()).isNotEqualTo(m1);
        assertThat(disp.body().get("notTravelledHistory")).hasSize(1);
        List<String> types = new ArrayList<>();
        after.get("movements").forEach(m -> types.add(m.get("type").asText()));
        assertThat(types).contains("NO_VIAJA", "MANIFIESTO_ASIGNADO").doesNotContain("DESPACHO");

        // PDF del manifiesto
        MockHttpServletResponse mp = pdf(ops, "/api/manifests/" + m1 + "/pdf");
        assertThat(mp.getStatus()).isEqualTo(200);
        String mt = text(mp.getContentAsByteArray());
        assertThat(mt).contains("OAX-1", "NO viajan", "No cupo en la unidad", "Autorizó salida");

        // Reporte con filtros por ruta y productividad por usuario (nombre, no correo)
        JsonNode sum = call(GET, "/api/reports/summary?shippingRouteId=" + r1, admin, null).body();
        assertThat(sum.get("generated").asInt()).isEqualTo(2);
        assertThat(sum.get("byCreator").get(0).get("user_name").asText()).isEqualTo("Usuario REMITENTE");

        // Gráfica del día del remitente: porcentajes que suman 100
        JsonNode day = call(GET, "/api/reports/today", remi, null).body();
        assertThat(day.get("total").asInt()).isEqualTo(3);
        assertThat(day.get("inTransit").asInt()).isEqualTo(1);
        assertThat(day.get("pending").asInt()).isEqualTo(2);
        assertThat(day.get("deliveredPct").asInt() + day.get("inTransitPct").asInt() + day.get("pendingPct").asInt()).isEqualTo(100);
    }

    private static byte[] reporte() throws Exception {
        String[][] rows = {
            {"Nombre de cliente", "Pedido", "Dirección", "LPN", "Tipo LPN", "Carga", "Ruta"},
            {"FERRETERIA UNO", "5001", "AV UNO, 10, Centro, Oaxaca, , Mexico, Oaxaca, 68000", "L-1", "Caja", "CARGA-9", "80"},
            {" ", " ", " ", "L-2", "Caja", "CARGA-9", " "},
            {" ", " ", " ", "L-3", "Sobre", "CARGA-9", " "},
            {"REFACCIONES DOS", "5002", "CALLE 2, 5, Norte, Oaxaca, , Mexico, Oaxaca, 68010", "L-4", "Tambo", "CARGA-9", "80"},
            {"CLIENTE EXCLUIDO", "5003", "CALLE 3, 1, Sur, Oaxaca, , Mexico, Oaxaca, 68020", "L-5", "Caja", "CARGA-9", "80"},
            {" ", " ", "Total de bultos: 5", null, null, null, null},
        };
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Hoja1");
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i);
                for (int j = 0; j < rows[i].length; j++) if (rows[i][j] != null) r.createCell(j).setCellValue(rows[i][j]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void importacionMasivaRevisadaEIdempotente() throws Exception {
        String admin = admin();
        long r1 = route(admin, "IMP-1");
        long sender = senderWithRoute(admin, "Remitente importacion", r1);
        String remi = user(admin, "REMITENTE", sender, null);
        var res = mvc.perform(multipart("/api/guides/import/preview")
                .file(new MockMultipartFile("file", "carga.xlsx", "application/octet-stream", reporte()))
                .header("Authorization", "Bearer " + remi).header("X-Requested-With", "test")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode p = json.readTree(res.getContentAsByteArray());
        assertThat(p.get("fileKey").asText()).isEqualTo("CARGA-9");
        assertThat(p.get("declaredPackages").asInt()).isEqualTo(5);
        // La previsualización no guarda nada
        assertThat(call(GET, "/api/guides?senderId=" + sender, admin, null).body().get("totalElements").asInt()).isZero();

        List<Map<String, Object>> groups = new ArrayList<>();
        for (JsonNode grp : p.get("groups")) {
            String name = grp.get("clientName").asText();
            List<Map<String, Object>> items = new ArrayList<>();
            grp.get("types").forEach(t -> items.add(Map.of("type", t.get("name").asText(), "quantity", t.get("quantity").asInt())));
            List<Map<String, Object>> pk = new ArrayList<>();
            grp.get("packageLines").forEach(l -> pk.add(Map.of("lpn", l.get("lpn").asText(), "type", l.get("type").asText(), "order", l.get("order").asText())));
            Map<String, Object> g = new HashMap<>();
            g.put("requestId", grp.get("requestId").asText());
            g.put("include", !name.equals("CLIENTE EXCLUIDO"));
            // Corrección del usuario en la tabla: nombre y subruta
            g.put("clientName", name.equals("REFACCIONES DOS") ? "Refacciones Dos del Valle" : name);
            g.put("address", json.convertValue(grp.get("address"), Map.class));
            g.put("orders", String.join(", ", json.convertValue(grp.get("orders"), List.class)));
            g.put("route", "80");
            g.put("subRoute", "OAX-07");
            g.put("destinationCode", "OAX");
            g.put("items", items);
            g.put("packages", pk);
            groups.add(g);
        }
        Map<String, Object> commit = Map.of("fileKey", p.get("fileKey").asText(), "fileName", "carga.xlsx", "load", "CARGA-9",
                "declaredPackages", 5, "confirm", true, "groups", groups);
        Res c = call(POST, "/api/guides/import/commit", remi, commit);
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        assertThat(c.body().get("created").asInt()).isEqualTo(2);
        assertThat(c.body().get("excluded").asInt()).isEqualTo(1);
        assertThat(c.body().get("committedPieces").asInt()).isEqualTo(4);
        assertThat(c.body().get("excludedPieces").asInt()).isEqualTo(1);
        long batch = c.body().get("batchId").asLong();

        JsonNode created = null;
        for (JsonNode row : c.body().get("rows")) if (row.get("clientName").asText().equals("FERRETERIA UNO")) created = row;
        JsonNode guide = call(GET, "/api/guides/" + created.get("guideId").asLong(), remi, null).body();
        assertThat(guide.get("packageCount").asInt()).isEqualTo(3);
        assertThat(guide.get("subRoute").asText()).isEqualTo("OAX-07");
        assertThat(guide.get("packages")).hasSize(3);
        assertThat(guide.get("packages").get(0).get("lpn").asText()).isEqualTo("L-1");
        assertThat(guide.get("importBatchId").asLong()).isEqualTo(batch);
        assertThat(guide.get("manifest").get("shippingRoute").get("id").asLong()).isEqualTo(r1);

        // Reintento (doble clic o recarga): no duplica
        Res again = call(POST, "/api/guides/import/commit", remi, commit);
        assertThat(again.body().get("created").asInt()).isZero();
        assertThat(again.body().get("skipped").asInt()).isEqualTo(2);
        assertThat(again.body().get("batchId").asLong()).isEqualTo(batch);
        // Otro usuario con el mismo archivo: se detecta por número de pedido
        String otro = user(admin, "REMITENTE", sender, null);
        Res dup = call(POST, "/api/guides/import/commit", otro, commit);
        assertThat(dup.body().get("created").asInt()).isZero();
        assertThat(dup.body().get("rows").toString()).contains("SKIPPED_DUPLICATE");
        assertThat(call(GET, "/api/guides?senderId=" + sender, admin, null).body().get("totalElements").asInt()).isEqualTo(2);

        // Comparación contra el archivo en la revisión del manifiesto
        JsonNode man = call(GET, "/api/manifests/" + guide.get("manifest").get("id").asLong(), admin, null).body();
        JsonNode b = man.get("review").get("batches").get(0);
        assertThat(b.get("declaredPackages").asInt()).isEqualTo(5);
        assertThat(b.get("piecesInManifest").asInt()).isEqualTo(4);
        assertThat(b.get("difference").asInt()).isEqualTo(1);
    }
}
