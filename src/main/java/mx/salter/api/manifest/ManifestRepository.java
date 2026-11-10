package mx.salter.api.manifest;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManifestRepository extends JpaRepository<Manifest, Long> {

    @EntityGraph(attributePaths = {"shift", "sender", "createdBy"})
    @Query("""
            select m from Manifest m
            where (:status is null or m.status = :status)
              and (:shiftId is null or m.shift.id = :shiftId)
              and (:senderId is null or m.sender.id = :senderId)
              and m.operationDate >= :from and m.operationDate <= :to
            """)
    Page<Manifest> search(@Param("status") Manifest.Status status, @Param("shiftId") Long shiftId,
                          @Param("senderId") Long senderId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                          Pageable pageable);

    @EntityGraph(attributePaths = {"shift", "sender", "createdBy", "closedBy", "guides", "guides.guide"})
    @Query("select m from Manifest m where m.id = :id")
    Optional<Manifest> findDetailed(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Manifest m where m.id = :id")
    Optional<Manifest> lockById(@Param("id") Long id);
}
