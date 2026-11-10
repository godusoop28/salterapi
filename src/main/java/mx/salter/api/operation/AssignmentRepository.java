package mx.salter.api.operation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssignmentRepository extends JpaRepository<CourierAssignment, Long> {

    @EntityGraph(attributePaths = "courier")
    Optional<CourierAssignment> findByGuideIdAndActiveTrue(Long guideId);

    @EntityGraph(attributePaths = "courier")
    List<CourierAssignment> findByGuideIdInAndActiveTrue(Collection<Long> guideIds);

    @EntityGraph(attributePaths = "guide")
    @Query("""
            select a from CourierAssignment a
            where a.courier.id = :courierId and a.active = true
            order by a.routeOrder asc nulls last, a.assignedAt asc
            """)
    List<CourierAssignment> findActiveByCourier(@Param("courierId") Long courierId);

    @Query("select count(a) from CourierAssignment a where a.courier.id = :courierId and a.active = true")
    long countActiveByCourier(@Param("courierId") Long courierId);

    boolean existsByGuideIdAndCourierIdAndActiveTrue(Long guideId, Long courierId);

    boolean existsByGuideIdAndCourierId(Long guideId, Long courierId);

    @EntityGraph(attributePaths = "guide")
    @Query("""
            select a from CourierAssignment a
            where a.courier.id = :courierId and a.assignedAt >= :from
            order by a.assignedAt desc
            """)
    List<CourierAssignment> findHistory(@Param("courierId") Long courierId, @Param("from") java.time.Instant from);
}
