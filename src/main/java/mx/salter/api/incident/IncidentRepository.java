package mx.salter.api.incident;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

    @Query("select i from Incident i where i.guide.id = :guideId and i.status <> mx.salter.api.incident.Incident.Status.RESUELTA")
    Optional<Incident> findOpenByGuide(@Param("guideId") Long guideId);

    List<Incident> findByGuideIdOrderByCreatedAtDesc(Long guideId);

    @EntityGraph(attributePaths = {"guide", "notes"})
    @Query("select i from Incident i where i.id = :id")
    Optional<Incident> findDetailed(@Param("id") Long id);
}
