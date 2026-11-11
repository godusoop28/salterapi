package mx.salter.api.attachment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import mx.salter.api.audit.AuditService;
import mx.salter.api.common.ApiException;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideAccess;
import mx.salter.api.guide.GuideRepository;
import mx.salter.api.incident.Incident;
import mx.salter.api.incident.IncidentRepository;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Evidencias y archivos (OPCIONALES hasta que el cliente defina si son obligatorios).
 * Se valida tamaño, tipo declarado y firma real del archivo.
 */
@RestController
@RequestMapping("/api/attachments")
@Tag(name = "Archivos y evidencias")
public class AttachmentController {
    public record AttachmentDto(Long id, Attachment.Category category, String filename, String contentType, long sizeBytes, Instant createdAt) {}

    private final AttachmentRepository repo;
    private final GuideRepository guides;
    private final IncidentRepository incidents;
    private final GuideAccess access;
    private final AuditService audit;
    private final FileStorage storage;
    private final long maxBytes;
    private final Set<String> allowed;

    public AttachmentController(AttachmentRepository repo, GuideRepository guides, IncidentRepository incidents, GuideAccess access,
                                AuditService audit, FileStorage storage, @Value("${salter.uploads.max-bytes:5242880}") long maxBytes,
                                @Value("${salter.uploads.allowed-types:image/jpeg,image/png,image/webp,application/pdf}") String allowed) {
        this.repo = repo;
        this.guides = guides;
        this.incidents = incidents;
        this.access = access;
        this.audit = audit;
        this.storage = storage;
        this.maxBytes = maxBytes;
        this.allowed = Arrays.stream(allowed.split(",")).map(String::trim).collect(Collectors.toSet());
    }

    @GetMapping("/{ownerType}/{ownerId}")
    @Transactional(readOnly = true)
    public List<AttachmentDto> list(@PathVariable Attachment.OwnerType ownerType, @PathVariable Long ownerId) {
        requireOwnerAccess(ownerType, ownerId, false);
        return repo.listMeta(ownerType, ownerId).stream()
                .map(m -> new AttachmentDto(m.getId(), m.getCategory(), m.getFilename(), m.getContentType(), m.getSizeBytes(), m.getCreatedAt()))
                .toList();
    }

    @PostMapping(value = "/{ownerType}/{ownerId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    @Operation(summary = "Adjunta un archivo (JPG, PNG, WEBP o PDF; máx. 5 MB).")
    public ResponseEntity<AttachmentDto> upload(@PathVariable Attachment.OwnerType ownerType, @PathVariable Long ownerId,
                                                @RequestParam Attachment.Category category, @RequestPart("file") MultipartFile file) throws IOException {
        requireOwnerAccess(ownerType, ownerId, true);
        if (file.isEmpty()) throw ApiException.badRequest("EMPTY_FILE", "El archivo está vacío.");
        if (file.getSize() > maxBytes) throw ApiException.badRequest("FILE_TOO_LARGE", "El archivo excede " + (maxBytes / 1024 / 1024) + " MB.");
        byte[] data = file.getBytes();
        String detected = detect(data);
        if (detected == null || !allowed.contains(detected)) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Tipo de archivo no permitido. Usa JPG, PNG, WEBP o PDF.");
        }
        Attachment a = new Attachment();
        a.setOwnerType(ownerType);
        a.setOwnerId(ownerId);
        a.setCategory(category);
        String name = file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._\\- ]", "_");
        a.setFilename(name.length() > 200 ? name.substring(0, 200) : name);
        a.setContentType(detected);
        a.setSizeBytes(data.length);
        a.setSha256(sha256(data));
        if (storage.external()) {
            FileStorage.Stored st = storage.upload(data, detected, ownerType.name().toLowerCase() + "/" + ownerId);
            a.setStorage("CLOUDINARY");
            a.setStorageKey(st.key());
            a.setResourceType(st.resourceType());
            a.setStorageVersion(st.version());
        } else {
            a.setData(data);
        }
        a.setUploadedBy(CurrentUser.get().userId());
        repo.save(a);
        audit.record("ATTACHMENT_UPLOADED", ownerType.name(), ownerId, Map.of("attachmentId", a.getId(), "category", category.name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AttachmentDto(a.getId(), a.getCategory(), a.getFilename(), a.getContentType(), a.getSizeBytes(), a.getCreatedAt()));
    }

    @GetMapping("/file/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        Attachment a = repo.findById(id).orElseThrow(() -> ApiException.notFound("Archivo"));
        requireOwnerAccess(a.getOwnerType(), a.getOwnerId(), false);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(a.getFilename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .body("CLOUDINARY".equals(a.getStorage()) ? storage.download(a) : a.getData());
    }

    private void requireOwnerAccess(Attachment.OwnerType type, Long ownerId, boolean write) {
        SalterPrincipal p = CurrentUser.get();
        Guide g;
        if (type == Attachment.OwnerType.GUIDE) {
            g = guides.findById(ownerId).orElseThrow(() -> ApiException.notFound("Guía"));
        } else {
            Incident i = incidents.findById(ownerId).orElseThrow(() -> ApiException.notFound("Incidencia"));
            if (!p.has(Permission.INCIDENTS_VIEW) && !i.getReportedBy().equals(p.userId())) throw ApiException.notFound("Incidencia");
            g = i.getGuide();
        }
        access.requireView(p, g);
        if (write && !(p.hasAny(Permission.GUIDES_VIEW_ALL, Permission.DELIVERIES_ANY, Permission.RECEPTION_CONFIRM,
                Permission.INCIDENTS_CREATE) || access.isAssignedCourier(p, g))) {
            throw ApiException.forbidden("No puedes adjuntar archivos a este registro.");
        }
    }

    static String detect(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        if (d.length > 4 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') return "application/pdf";
        return null;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
