package mx.salter.api.attachment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    interface Meta {
        Long getId();
        Attachment.Category getCategory();
        String getFilename();
        String getContentType();
        long getSizeBytes();
        java.time.Instant getCreatedAt();
    }

    @Query("""
            select a.id as id, a.category as category, a.filename as filename, a.contentType as contentType,
                   a.sizeBytes as sizeBytes, a.createdAt as createdAt
            from Attachment a where a.ownerType = :type and a.ownerId = :ownerId order by a.createdAt
            """)
    List<Meta> listMeta(@Param("type") Attachment.OwnerType type, @Param("ownerId") Long ownerId);
}
