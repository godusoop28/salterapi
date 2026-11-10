package mx.salter.api.user;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import mx.salter.api.audit.AuditService;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PageResponse;
import mx.salter.api.common.Paging;
import mx.salter.api.common.Text;
import mx.salter.api.operation.Courier;
import mx.salter.api.operation.CourierRepository;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderRepository;
import mx.salter.api.user.UserDtos.CreateUserRequest;
import mx.salter.api.user.UserDtos.PermissionDto;
import mx.salter.api.user.UserDtos.RoleDto;
import mx.salter.api.user.UserDtos.UpdateUserRequest;
import mx.salter.api.user.UserDtos.UserDto;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private static final Set<String> ALL_PERMISSIONS =
            Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final UserRepository users;
    private final RoleRepository roles;
    private final SenderRepository senders;
    private final CourierRepository couriers;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    public UserService(UserRepository users, RoleRepository roles, SenderRepository senders, CourierRepository couriers,
                       PasswordEncoder encoder, AuditService audit, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.senders = senders;
        this.couriers = couriers;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserDto> search(String q, String role, Boolean active, Integer page, Integer size) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return PageResponse.of(users.search(like, role, active, Paging.of(page, size, Sort.by("fullName"))), this::toDto);
    }

    @Transactional(readOnly = true)
    public UserDto get(Long id) {
        return toDto(users.findForPrincipal(id).orElseThrow(() -> ApiException.notFound("Usuario")));
    }

    @Transactional
    public UserDto create(CreateUserRequest req) {
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw ApiException.conflict("EMAIL_TAKEN", "Ya existe un usuario con ese correo.");
        }
        PasswordPolicy.validate(req.password());
        User u = new User();
        u.setEmail(req.email().trim().toLowerCase());
        u.setFullName(req.fullName().trim());
        u.setPhone(Text.trimToNull(req.phone()));
        u.setPasswordHash(encoder.encode(req.password()));
        applyRoleAndLinks(u, req.role(), req.senderId(), req.courierId());
        u.setGrantedPermissions(validPermissions(req.grantedPermissions()));
        u.setRevokedPermissions(validPermissions(req.revokedPermissions()));
        users.save(u);
        audit.record("USER_CREATED", "USER", u.getId(), details(u));
        return toDto(u);
    }

    @Transactional
    public UserDto update(Long id, UpdateUserRequest req) {
        User u = users.findForPrincipal(id).orElseThrow(() -> ApiException.notFound("Usuario"));
        if (u.getVersion() != req.version()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "El usuario fue modificado por otra persona. Recarga la información.");
        }
        if (users.existsByEmailIgnoreCaseAndIdNot(req.email().trim(), id)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Ya existe un usuario con ese correo.");
        }
        boolean self = id.equals(CurrentUser.get().userId());
        if (self && Boolean.FALSE.equals(req.active())) {
            throw ApiException.business("SELF_DEACTIVATION", "No puedes desactivar tu propia cuenta.");
        }
        if (self && !req.role().equals(u.getRole().getCode())) {
            throw ApiException.business("SELF_ROLE_CHANGE", "No puedes cambiar tu propio rol.");
        }
        Map<String, Object> before = details(u);
        u.setEmail(req.email().trim().toLowerCase());
        u.setFullName(req.fullName().trim());
        u.setPhone(Text.trimToNull(req.phone()));
        String previousRole = u.getRole().getCode();
        applyRoleAndLinks(u, req.role(), req.senderId(), req.courierId());
        boolean deactivated = req.active() != null && !req.active() && u.isActive();
        if (req.active() != null) u.setActive(req.active());
        u.getGrantedPermissions().clear();
        u.getGrantedPermissions().addAll(validPermissions(req.grantedPermissions()));
        u.getRevokedPermissions().clear();
        u.getRevokedPermissions().addAll(validPermissions(req.revokedPermissions()));
        if (deactivated || !previousRole.equals(u.getRole().getCode())) {
            u.setTokenVersion(u.getTokenVersion() + 1);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("before", before);
        d.put("after", details(u));
        audit.record("USER_UPDATED", "USER", u.getId(), d);
        users.flush();
        return toDto(u);
    }

    @Transactional
    public void resetPassword(Long id, String password) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuario"));
        PasswordPolicy.validate(password);
        u.setPasswordHash(encoder.encode(password));
        u.setTokenVersion(u.getTokenVersion() + 1);
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        audit.record("USER_PASSWORD_RESET", "USER", u.getId(), null);
    }

    @Transactional
    public void unlock(Long id) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuario"));
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        audit.record("USER_UNLOCKED", "USER", u.getId(), null);
    }

    @Transactional(readOnly = true)
    public List<RoleDto> roles() {
        return roles.findAllWithPermissions().stream().map(UserService::toRoleDto).toList();
    }

    @Transactional
    public RoleDto updateRolePermissions(Long roleId, Set<String> permissions) {
        Role r = roles.findById(roleId).orElseThrow(() -> ApiException.notFound("Rol"));
        Set<String> valid = validPermissions(permissions);
        if ("ADMIN".equals(r.getCode()) && !valid.containsAll(Set.of("USERS_MANAGE", "ROLES_MANAGE"))) {
            throw ApiException.business("ADMIN_LOCKOUT", "El rol Administrador debe conservar la gestión de usuarios y roles.");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("before", r.getPermissions().stream().sorted().toList());
        r.getPermissions().clear();
        r.getPermissions().addAll(valid);
        d.put("after", valid.stream().sorted().toList());
        audit.record("ROLE_PERMISSIONS_UPDATED", "ROLE", r.getCode(), d);
        return toRoleDto(r);
    }

    public List<PermissionDto> permissionCatalog() {
        return Arrays.stream(Permission.values()).map(p -> new PermissionDto(p.name(), p.getDescription())).toList();
    }

    private void applyRoleAndLinks(User u, String roleCode, Long senderId, Long courierId) {
        Role role = roles.findByCode(roleCode).orElseThrow(() -> ApiException.badRequest("INVALID_ROLE", "Rol no válido."));
        u.setRole(role);
        Sender sender = null;
        if (senderId != null) {
            sender = senders.findById(senderId).orElseThrow(() -> ApiException.badRequest("INVALID_SENDER", "Remitente no válido."));
        }
        if ("REMITENTE".equals(roleCode) && sender == null) {
            throw ApiException.badRequest("SENDER_REQUIRED", "Un usuario remitente debe tener un remitente empresarial asignado.");
        }
        u.setSender(sender);
        Courier courier = null;
        if (courierId != null) {
            courier = couriers.findById(courierId).orElseThrow(() -> ApiException.badRequest("INVALID_COURIER", "Repartidor no válido."));
            if (users.existsByCourierIdAndIdNot(courierId, u.getId() == null ? -1L : u.getId())) {
                throw ApiException.conflict("COURIER_LINKED", "Ese repartidor ya está vinculado a otra cuenta.");
            }
        }
        if ("REPARTIDOR".equals(roleCode) && courier == null) {
            throw ApiException.badRequest("COURIER_REQUIRED", "Un usuario repartidor debe estar vinculado a un repartidor.");
        }
        u.setCourier(courier);
    }

    private static Set<String> validPermissions(Set<String> input) {
        if (input == null) return new HashSet<>();
        Set<String> out = new HashSet<>();
        for (String p : input) {
            if (!ALL_PERMISSIONS.contains(p)) throw ApiException.badRequest("INVALID_PERMISSION", "Permiso no válido: " + p);
            out.add(p);
        }
        return out;
    }

    private Map<String, Object> details(User u) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("email", u.getEmail());
        d.put("role", u.getRole().getCode());
        d.put("active", u.isActive());
        d.put("senderId", u.getSender() == null ? null : u.getSender().getId());
        d.put("courierId", u.getCourier() == null ? null : u.getCourier().getId());
        d.put("granted", u.getGrantedPermissions().stream().sorted().toList());
        d.put("revoked", u.getRevokedPermissions().stream().sorted().toList());
        return d;
    }

    UserDto toDto(User u) {
        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(clock.instant());
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole().getCode(), u.getRole().getName(),
                u.isActive(),
                u.getSender() == null ? null : new Ref(u.getSender().getId(), u.getSender().getName()),
                u.getCourier() == null ? null : new Ref(u.getCourier().getId(), u.getCourier().getName()),
                Set.copyOf(u.getGrantedPermissions()), Set.copyOf(u.getRevokedPermissions()),
                u.getLastLoginAt(), u.getCreatedAt(), locked, u.getVersion());
    }

    static RoleDto toRoleDto(Role r) {
        return new RoleDto(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.getPermissions().stream().sorted().toList());
    }
}
