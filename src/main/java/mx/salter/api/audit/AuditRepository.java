package mx.salter.api.audit;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRepository extends JpaRepository<AuditLog, Long> {
    @Query("""
            select a from AuditLog a
            where (:action is null or a.action = :action)
              and (:entityType is null or a.entityType = :entityType)
              and (:entityId is null or a.entityId = :entityId)
              and (:userId is null or a.userId = :userId)
              and a.occurredAt >= :from and a.occurredAt < :to
            """)
    Page<AuditLog> search(@Param("action") String action, @Param("entityType") String entityType,
                          @Param("entityId") String entityId, @Param("userId") Long userId,
                          @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    long countByAction(String action);
}
