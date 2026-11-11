package mx.salter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class FlujoOperativoTest extends IntegrationTestBase {

    @Test
    void loginCorrectoIncorrectoYSesion() throws Exception {
        assertThat(call(POST, "/api/auth/login", null, Map.of("email", "admin@test.local", "password", "incorrecta123")).status()).isEqualTo(401);
        assertThat(call(POST, "/api/auth/login", null, Map.of("email", "nadie@test.local", "password", "incorrecta123")).status()).isEqualTo(401);
        String t = admin();
        Res me = call(GET, "/api/auth/me", t, null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("role").asText()).isEqualTo("ADMIN");
        assertThat(call(GET, "/api/auth/me", "token-invalido", null).status()).isEqualTo(401);
        assertThat(call(GET, "/api/guides", null, null).status()).isEqualTo(401);
    }

    @Test
    void accesoRestringidoPorRol() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente roles");
        String cap = user(admin, "CAPTURISTA", sender, null);
        String rep = user(admin, "REPARTIDOR", null, createCourier(admin, "Rep roles"));
        assertThat(call(GET, "/api/users", cap, null).status()).isEqualTo(403);
        assertThat(call(PUT, "/api/settings", cap, Map.of("company.name", "X")).status()).isEqualTo(403);
        assertThat(call(GET, "/api/manifests", cap, null).status()).isEqualTo(403);
        assertThat(call(GET, "/api/reports/summary", rep, null).status()).isEqualTo(403);
        assertThat(call(POST, "/api/guides", rep, guideBody("X", false)).status()).isEqualTo(403);
        // Sin permiso de confirmar (no concedido por defecto al capturista)
        assertThat(call(POST, "/api/guides", cap, guideBody("Sin permiso", true)).status()).isEqualTo(403);
    }

    @Test
    void importacionYDeteccionDeDuplicados() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente import");
        String cap = user(admin, "CAPTURISTA", sender, null, "RECIPIENTS_IMPORT");
        List<Map<String, Object>> rows = List.of(
                Map.of("rowNumber", 2, "firstName", "Lucía", "lastName", "Hernández", "phone", "55 1111 2222", "zip", "01000"),
                Map.of("rowNumber", 3, "firstName", "LUCIA", "lastName", "HERNANDEZ", "phone", "5511112222"),
                Map.of("rowNumber", 4, "firstName", "Pedro", "lastName", "Soto", "phone", "5511112222"),
                Map.of("rowNumber", 5, "firstName", "", "zip", "12"));
        Res v = call(POST, "/api/recipients/import/validate", cap, Map.of("senderId", sender, "rows", rows));
        assertThat(v.status()).isEqualTo(200);
        assertThat(v.body().get("valid").asInt()).isEqualTo(1);
        assertThat(v.body().get("duplicates").asInt()).isEqualTo(1);          // mismo nombre + teléfono
        assertThat(v.body().get("probableDuplicates").asInt()).isEqualTo(1);  // mismo teléfono, otro nombre
        assertThat(v.body().get("errors").asInt()).isEqualTo(1);
        Res c = call(POST, "/api/recipients/import/commit", cap, Map.of("senderId", sender, "rows", rows));
        assertThat(c.body().get("created").asInt()).isEqualTo(1);
        Res again = call(POST, "/api/recipients/import/commit", cap, Map.of("senderId", sender, "rows", rows));
        assertThat(again.body().get("created").asInt()).isZero();
        // Alta manual duplicada exacta: 409
        Res dup = call(POST, "/api/recipients", cap, Map.of("firstName", "lucia", "lastName", "hernandez", "phone", "5511112222"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("code").asText()).isEqualTo("RECIPIENT_DUPLICATE");
        // Búsqueda sin acentos
        Res s = call(GET, "/api/recipients?q=lucia hernandez", cap, null);
        assertThat(s.body().get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void guiasConDestinatarioTemporalRecurrenteYFolios() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente guías");
        String cap = user(admin, "CAPTURISTA", sender, null, "GUIDES_CONFIRM");
        Res g1 = call(POST, "/api/guides", cap, guideBody("Temporal", true));
        assertThat(g1.status()).isEqualTo(201);
        assertThat(g1.body().get("status").asText()).isEqualTo("CONFIRMADA");
        assertThat(g1.body().get("recipientOneTime").asBoolean()).isTrue();
        assertThat(g1.body().get("folio").asText()).matches("^RG\\d{9}$");

        Map<String, Object> recurrent = Map.of("clientRequestId", UUID.randomUUID().toString(),
                "newRecipient", Map.of("firstName", "Recurrente", "phone", "2229998877",
                        "address", Map.of("street", "Calle 2", "city", "Puebla"), "saveToCatalog", true),
                "items", List.of(Map.of("description", "Caja", "quantity", 1)),
                "shipping", Map.of("destinationCode", "PBA"), "confirm", false);
        Res g2 = call(POST, "/api/guides", cap, recurrent);
        assertThat(g2.body().get("recipientId").asLong()).isPositive();
        assertThat(g2.body().has("folio")).isFalse(); // borrador sin folio
        Res conf = call(POST, "/api/guides/" + g2.body().get("id").asLong() + "/confirm", cap, null);
        assertThat(conf.status()).isEqualTo(200);
        assertThat(conf.body().get("folio").asText()).isNotEqualTo(g1.body().get("folio").asText());
        // Idempotencia
        Res again = call(POST, "/api/guides", cap, recurrent);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("id").asLong()).isEqualTo(g2.body().get("id").asLong());
    }

    @Test
    void guiaConFormatoOficialDelCliente() throws Exception {
        String admin = admin();
        long sender = call(POST, "/api/senders", admin, Map.of("name", "Auto Todo Mexicana", "kind", "EMPRESA",
                "plazaCode", "pba", "address", Map.of("city", "Puebla", "state", "Puebla"))).body().get("id").asLong();
        Res s = call(GET, "/api/senders/" + sender, admin, null);
        assertThat(s.body().get("effectiveGuidePrefix").asText()).isEqualTo("ATM");
        String sup = user(admin, "SUPERVISOR", null, null);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("clientRequestId", UUID.randomUUID().toString(), "senderId", sender,
                "newRecipient", Map.of("firstName", "Jaime Arturo", "lastName", "Avelar Morales",
                        "address", Map.of("street", "Nicolas Bravo 204", "city", "Jojutla", "state", "Morelos"), "saveToCatalog", false),
                "items", List.of(Map.of("description", "Caja", "quantity", 8, "weightKg", 10)),
                "packageCount", 8, "cdReference", "CVA-01",
                "shipping", Map.of("destinationCode", "oax", "dimensions", "30 x 20 x 15", "serviceType", "ORDINARIO"),
                "confirm", true));
        Res g = call(POST, "/api/guides", sup, body);
        assertThat(g.status()).isEqualTo(201);
        String folio = g.body().get("folio").asText();
        assertThat(folio).matches("^ATM\\d{9}$");
        assertThat(g.body().get("shipping").get("originCode").asText()).isEqualTo("PBA");
        assertThat(g.body().get("shipping").get("destinationCode").asText()).isEqualTo("OAX");
        assertThat(g.body().get("shipping").get("commitmentDate").asText())
                .isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("America/Mexico_City")).plusDays(1).toString());
        // Búsqueda por folio impreso ("ATM 000000001") y por lectura del PDF417 ("ATM000000001|PBA|OAX|…")
        String printed = folio.substring(0, 3) + " " + folio.substring(3);
        assertThat(call(GET, "/api/guides/by-folio/" + printed, sup, null).status()).isEqualTo(200);
        assertThat(call(GET, "/api/guides/by-folio/" + folio + "|PBA|OAX|8", sup, null).status()).isEqualTo(200);
        // Documento PDF con el formato oficial
        var pdf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/guides/" + g.body().get("id").asLong() + "/pdf").header("Authorization", "Bearer " + sup)).andReturn().getResponse();
        assertThat(pdf.getStatus()).isEqualTo(200);
        byte[] bytes = pdf.getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        java.nio.file.Files.write(java.nio.file.Path.of("target", "guia-ejemplo.pdf"), bytes);
        // Sin plaza de destino no se confirma
        Map<String, Object> noDest = new java.util.HashMap<>(body);
        noDest.put("clientRequestId", UUID.randomUUID().toString());
        noDest.put("shipping", Map.of("dimensions", "10x10x10"));
        Res bad = call(POST, "/api/guides", sup, noDest);
        assertThat(bad.body().get("code").asText()).isEqualTo("INCOMPLETE_GUIDE");
        // Servicio inválido
        noDest.put("shipping", Map.of("destinationCode", "OAX", "serviceType", "PATITO"));
        assertThat(call(POST, "/api/guides", sup, noDest).body().get("code").asText()).isEqualTo("INVALID_SERVICE");
        assertThat(call(GET, "/api/guides/services", sup, null).body().get(0).get("code").asText()).isEqualTo("ORDINARIO");
    }

    @Test
    void foliosUnicosConSolicitudesSimultaneas() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente concurrencia");
        String sup = user(admin, "SUPERVISOR", null, null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int n = i;
            tasks.add(() -> {
                Map<String, Object> b = new java.util.HashMap<>(guideBody("Concurrente " + n, true));
                b.put("senderId", sender);
                Res r = call(POST, "/api/guides", sup, b);
                return r.status() == 201 ? r.body().get("folio").asText() : "ERROR " + r.status();
            });
        }
        Set<String> folios = new HashSet<>();
        for (Future<String> f : pool.invokeAll(tasks)) folios.add(f.get());
        pool.shutdown();
        assertThat(folios).hasSize(16).allMatch(f -> f.matches("^RC\\d{9}$"));
    }

    @Test
    void flujoCompletoManifiestoSalidaRecepcionEntregaYReportes() throws Exception {
        String admin = admin();
        long sender = createSender(admin, "Remitente flujo");
        long otherSender = createSender(admin, "Remitente ajeno");
        long courier = createCourier(admin, "Repartidor flujo");
        String cap = user(admin, "CAPTURISTA", sender, null, "GUIDES_CONFIRM");
        String sup = user(admin, "SUPERVISOR", null, null);
        String rep = user(admin, "REPARTIDOR", null, courier);
        String rec = user(admin, "RECEPCION", null, null);
        String remi = user(admin, "REMITENTE", sender, null);
        String remiOther = user(admin, "REMITENTE", otherSender, null);

        long g1 = call(POST, "/api/guides", cap, guideBody("Uno", true)).body().get("id").asLong();
        long g2 = call(POST, "/api/guides", cap, guideBody("Dos", true)).body().get("id").asLong();

        // Protección entre remitentes
        assertThat(call(GET, "/api/guides/" + g1, remi, null).status()).isEqualTo(200);
        assertThat(call(GET, "/api/guides/" + g1, remiOther, null).status()).isEqualTo(404);
        assertThat(call(GET, "/api/guides/" + g1, rep, null).status()).isEqualTo(404);

        Res man = call(POST, "/api/manifests", sup, Map.of("operationDate", "2026-11-10", "guideIds", List.of(g1, g2)));
        assertThat(man.status()).isEqualTo(201);
        long mid = man.body().get("id").asLong();
        // Una guía no puede estar en dos manifiestos
        assertThat(call(POST, "/api/manifests", sup, Map.of("operationDate", "2026-11-10", "guideIds", List.of(g1))).status()).isEqualTo(422);
        man = call(POST, "/api/manifests/" + mid + "/close?version=" + man.body().get("version").asLong(), sup, null);
        assertThat(status(cap, g1)).isEqualTo("LISTA_SALIDA");

        // Dos despachos simultáneos: sólo uno procede
        long version = man.body().get("version").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> dispatch = () -> call(POST, "/api/manifests/" + mid + "/dispatch", sup,
                Map.of("courierId", courier, "version", version)).status();
        List<Future<Integer>> fs = pool.invokeAll(List.of(dispatch, dispatch));
        pool.shutdown();
        List<Integer> codes = List.of(fs.get(0).get(), fs.get(1).get());
        assertThat(codes).containsOnlyOnce(200);
        assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(1);
        assertThat(status(cap, g1)).isEqualTo("EN_TRANSITO");

        // Repartidor ve sus asignaciones
        JsonNode asg = call(GET, "/api/courier/assignments", rep, null).body();
        assertThat(asg.size()).isEqualTo(2);

        // Recepción (no duplicable) y entrega final
        assertThat(call(POST, "/api/guides/" + g1 + "/delivery", rep, Map.of("receivedByName", "X")).status()).isEqualTo(422);
        assertThat(call(POST, "/api/guides/" + g1 + "/reception", rec, Map.of("receivedCount", 1)).status()).isEqualTo(200);
        assertThat(call(POST, "/api/guides/" + g1 + "/reception", rec, Map.of("receivedCount", 1)).status()).isEqualTo(409);
        assertThat(call(POST, "/api/guides/" + g1 + "/delivery", rep, Map.of("receivedByName", "Destinatario")).status()).isEqualTo(200);
        assertThat(status(sup, g1)).isEqualTo("ENTREGADA");
        // Transición no permitida
        assertThat(call(POST, "/api/guides/" + g1 + "/hold", sup, Map.of("reason", "x")).status()).isEqualTo(422);

        // Historial completo e inmutable
        JsonNode movs = call(GET, "/api/guides/" + g1 + "/movements", sup, null).body();
        List<String> types = new ArrayList<>();
        movs.forEach(m -> types.add(m.get("type").asText()));
        assertThat(types).containsSubsequence("CREACION", "CONFIRMACION", "MANIFIESTO", "DESPACHO", "RECEPCION", "ENTREGA");

        // Reportes coinciden con los movimientos
        JsonNode sum = call(GET, "/api/reports/summary?senderId=" + sender, sup, null).body();
        assertThat(sum.get("generated").asLong()).isEqualTo(2);
        assertThat(sum.get("dispatched").asLong()).isEqualTo(2);
        assertThat(sum.get("received").asLong()).isEqualTo(1);
        assertThat(sum.get("delivered").asLong()).isEqualTo(1);
        assertThat(sum.get("inTransit").asLong()).isEqualTo(1);
        // El remitente ajeno no puede ver datos de otro aunque lo pida
        JsonNode other = call(GET, "/api/reports/summary?senderId=" + sender, remiOther, null).body();
        assertThat(other.get("generated").asLong()).isZero();
    }

    private String status(String token, long guideId) throws Exception {
        return call(GET, "/api/guides/" + guideId, token, null).body().get("status").asText();
    }
}
