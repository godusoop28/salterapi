package mx.salter.api.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"role", "role.permissions", "grantedPermissions", "revokedPermissions", "sender", "courier"})
    @Query("select u from User u where u.id = :id")
    Optional<User> findForPrincipal(@Param("id") Long id);

    @EntityGraph(attributePaths = {"role", "role.permissions", "grantedPermissions", "revokedPermissions", "sender", "courier"})
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailForLogin(@Param("email") String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    @EntityGraph(attributePaths = {"role", "sender", "courier"})
    @Query("""
            select u from User u
            where (:q is null or lower(u.fullName) like :q or lower(u.email) like :q)
              and (:roleCode is null or u.role.code = :roleCode)
              and (:active is null or u.active = :active)
            """)
    Page<User> search(@Param("q") String q, @Param("roleCode") String roleCode, @Param("active") Boolean active, Pageable pageable);

    boolean existsByCourierIdAndIdNot(Long courierId, Long id);
}
