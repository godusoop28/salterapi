package mx.salter.api.user;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.salter.api.common.PageResponse;
import mx.salter.api.user.UserDtos.CreateUserRequest;
import mx.salter.api.user.UserDtos.PermissionDto;
import mx.salter.api.user.UserDtos.ResetPasswordRequest;
import mx.salter.api.user.UserDtos.RoleDto;
import mx.salter.api.user.UserDtos.UpdateRolePermissionsRequest;
import mx.salter.api.user.UserDtos.UpdateUserRequest;
import mx.salter.api.user.UserDtos.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Usuarios, roles y permisos")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public PageResponse<UserDto> search(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
                                        @RequestParam(required = false) Boolean active,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.search(q, role, active, page, size);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public UserDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<Void> unlock(@PathVariable Long id) {
        service.unlock(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('USERS_MANAGE','ROLES_MANAGE')")
    public List<RoleDto> roles() {
        return service.roles();
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('ROLES_MANAGE')")
    public RoleDto updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRolePermissionsRequest req) {
        return service.updateRolePermissions(id, req.permissions());
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAnyAuthority('USERS_MANAGE','ROLES_MANAGE')")
    public List<PermissionDto> permissions() {
        return service.permissionCatalog();
    }
}
