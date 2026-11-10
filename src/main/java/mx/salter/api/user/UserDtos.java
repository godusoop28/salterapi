package mx.salter.api.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import mx.salter.api.auth.AuthDtos.Ref;

public final class UserDtos {
    private UserDtos() {}

    public record UserDto(Long id, String email, String fullName, String phone, String role, String roleName,
                          boolean active, Ref sender, Ref courier, Set<String> grantedPermissions,
                          Set<String> revokedPermissions, Instant lastLoginAt, Instant createdAt, boolean locked,
                          long version) {}

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 30) String phone,
            @NotBlank String role,
            @NotBlank @Size(min = 10, max = 200) String password,
            Long senderId,
            Long courierId,
            Set<String> grantedPermissions,
            Set<String> revokedPermissions) {}

    public record UpdateUserRequest(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 30) String phone,
            @NotBlank String role,
            Long senderId,
            Long courierId,
            Boolean active,
            Set<String> grantedPermissions,
            Set<String> revokedPermissions,
            @NotNull Long version) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 10, max = 200) String password) {}

    public record RoleDto(Long id, String code, String name, String description, List<String> permissions) {}

    public record UpdateRolePermissionsRequest(@NotNull Set<String> permissions) {}

    public record PermissionDto(String code, String description) {}
}
