package mx.salter.api.guide;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuideRepository extends JpaRepository<Guide, Long>, JpaSpecificationExecutor<Guide> {

    Optional<Guide> findByCreatedByIdAndClientRequestId(Long userId, UUID clientRequestId);

    Optional<Guide> findByFolioIgnoreCase(String folio);

    @EntityGraph(attributePaths = {"items", "shift", "createdBy", "confirmedBy", "cancelledBy"})
    @Query("select g from Guide g where g.id = :id")
    Optional<Guide> findDetailed(@Param("id") Long id);

    /** Bloqueo pesimista para operaciones de estado concurrentes (despacho, recepción, entrega). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Guide g where g.id = :id")
    Optional<Guide> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Guide g where g.id in :ids order by g.id")
    List<Guide> lockAllById(@Param("ids") Collection<Long> ids);
}
