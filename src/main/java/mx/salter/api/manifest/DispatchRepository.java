package mx.salter.api.manifest;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchRepository extends JpaRepository<Dispatch, Long> {
    Optional<Dispatch> findByManifestId(Long manifestId);

    boolean existsByManifestId(Long manifestId);
}
