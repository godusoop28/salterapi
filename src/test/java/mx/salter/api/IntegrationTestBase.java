package mx.salter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pruebas de integración con PostgreSQL real (embebido con zonky, sin Docker),
 * migraciones Flyway y la cadena de seguridad completa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
public abstract class IntegrationTestBase {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    protected record Res(int status, JsonNode body) {}

    protected Res call(HttpMethod method, String path, String token, Object body) throws Exception {
        var req = request(method, path).header("X-Requested-With", "test");
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        MvcResult r = mvc.perform(req).andReturn();
        String s = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Res(r.getResponse().getStatus(), s.isBlank() || !(s.startsWith("{") || s.startsWith("[")) ? null : json.readTree(s));
    }

    protected String login(String email, String password) throws Exception {
        Res r = call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", password));
        if (r.status() != 200) throw new AssertionError("Login falló para " + email + ": " + r.body());
        return r.body().get("token").asText();
    }

    protected String admin() throws Exception {
        return login("admin@test.local", "AdminPrueba2026");
    }

    protected long createSender(String admin, String name) throws Exception {
        return call(HttpMethod.POST, "/api/senders", admin, Map.of("name", name, "kind", "EMPRESA")).body().get("id").asLong();
    }

    protected long createCourier(String admin, String name) throws Exception {
        return call(HttpMethod.POST, "/api/couriers", admin, Map.of("name", name)).body().get("id").asLong();
    }

    /** Crea un usuario y devuelve su token. */
    protected String user(String admin, String role, Long senderId, Long courierId, String... grants) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        var body = new java.util.HashMap<String, Object>();
        body.put("email", email);
        body.put("fullName", "Usuario " + role);
        body.put("role", role);
        body.put("password", "Prueba2026x1");
        body.put("senderId", senderId);
        body.put("courierId", courierId);
        body.put("grantedPermissions", java.util.Set.of(grants));
        Res r = call(HttpMethod.POST, "/api/users", admin, body);
        if (r.status() != 201) throw new AssertionError("No se creó usuario: " + r.body());
        return login(email, "Prueba2026x1");
    }

    protected Map<String, Object> guideBody(String recipientName, boolean confirm) {
        return Map.of("clientRequestId", UUID.randomUUID().toString(),
                "newRecipient", Map.of("firstName", recipientName, "address", Map.of("street", "Calle 1", "city", "Puebla"), "saveToCatalog", false),
                "items", java.util.List.of(Map.of("description", "Caja", "quantity", 1, "unit", "PIEZA")),
                "shipping", Map.of("destinationCode", "OAX", "dimensions", "30x20x15"),
                "confirm", confirm);
    }
}
