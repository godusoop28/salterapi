package mx.salter.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Email @Size(max = 160) String email,
                               @NotBlank @Size(max = 200) String password) {}

    public record Ref(Long id, String name) {}

    public record MeResponse(Long id, String email, String fullName, String phone, String role, String roleName,
                             List<String> permissions, Ref sender, Ref courier, Instant lastLoginAt) {}

    public record LoginResponse(String token, Instant expiresAt, MeResponse user) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 10, max = 200, message = "La contraseña debe tener al menos 10 caracteres") String newPassword) {}
}
