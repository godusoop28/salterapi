package mx.salter.api.guide;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuideImportBatchRepository extends JpaRepository<GuideImportBatch, Long> {
    Optional<GuideImportBatch> findBySenderIdAndFileKey(Long senderId, String fileKey);
}
