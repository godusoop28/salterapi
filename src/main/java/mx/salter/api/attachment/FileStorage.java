package mx.salter.api.attachment;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Almacenamiento de evidencias. Con CLOUDINARY_URL (cloudinary://API_KEY:API_SECRET@CLOUD_NAME) los archivos se
 * suben a Cloudinary como recursos PRIVADOS ("authenticated"): no hay URL pública; la API los descarga con una URL
 * firmada y los entrega sólo a usuarios con permiso. Sin la variable, se guardan en PostgreSQL como antes.
 */
@Service
public class FileStorage {
    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    public record Stored(String key, String resourceType, Long version) {}

    private final Cloudinary cloudinary;
    private final String folder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public FileStorage(@Value("${CLOUDINARY_URL:}") String url, @Value("${salter.uploads.cloudinary-folder:salter}") String folder) {
        this.folder = folder;
        if (url == null || url.isBlank()) {
            this.cloudinary = null;
            log.info("Evidencias: almacenamiento en base de datos (CLOUDINARY_URL no definido)");
        } else {
            this.cloudinary = new Cloudinary(url.trim());
            this.cloudinary.config.secure = true;
            log.info("Evidencias: almacenamiento en Cloudinary (cuenta {})", cloudinary.config.cloudName);
        }
    }

    public boolean external() {
        return cloudinary != null;
    }

    public Stored upload(byte[] data, String contentType, String ownerPath) throws IOException {
        String resourceType = contentType.startsWith("image/") ? "image" : "raw";
        String publicId = folder + "/" + ownerPath + "/" + UUID.randomUUID() + (resourceType.equals("raw") ? ".pdf" : "");
        Map<?, ?> r = cloudinary.uploader().upload(data, ObjectUtils.asMap(
                "public_id", publicId, "resource_type", resourceType, "type", "authenticated", "overwrite", false));
        Object v = r.get("version");
        return new Stored(String.valueOf(r.get("public_id")), resourceType, v == null ? null : Long.valueOf(v.toString()));
    }

    public byte[] download(Attachment a) {
        if (cloudinary == null) throw new IllegalStateException("Cloudinary no está configurado (CLOUDINARY_URL).");
        var url = cloudinary.url().resourceType(a.getResourceType()).type("authenticated").signed(true);
        if (a.getStorageVersion() != null) url = url.version(a.getStorageVersion());
        String signed = url.generate(a.getStorageKey());
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(signed)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IllegalStateException("Cloudinary respondió " + res.statusCode());
            return res.body();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo descargar el archivo de Cloudinary", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
