package mx.salter.api.manifest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManifestGuideRepository extends JpaRepository<ManifestGuide, ManifestGuide.Key> {

    /** Manifiesto al que pertenece actualmente la guía (a lo más uno). */
    @EntityGraph(attributePaths = "manifest")
    Optional<ManifestGuide> findByGuideIdAndActiveTrue(Long guideId);

    @Query("select mg.guide.id from ManifestGuide mg where mg.active = true and mg.guide.id in :ids")
    List<Long> findAssignedGuideIds(@Param("ids") Collection<Long> ids);

    @Query("select coalesce(max(mg.position), 0) from ManifestGuide mg where mg.manifest.id = :manifestId")
    int maxPosition(@Param("manifestId") Long manifestId);

    @Query("select count(mg) from ManifestGuide mg where mg.manifest.id = :manifestId")
    long countByManifest(@Param("manifestId") Long manifestId);

    /** Conteo de guías de varios manifiestos en una sola consulta (evita N+1 en listados). */
    @Query("select mg.manifest.id, count(mg) from ManifestGuide mg where mg.active = true and mg.manifest.id in :ids group by mg.manifest.id")
    List<Object[]> countByManifestIds(@Param("ids") Collection<Long> ids);
}
