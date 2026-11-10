package mx.salter.api.security;

import mx.salter.api.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}

    public static SalterPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SalterPrincipal p) return p;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Inicia sesión para continuar.");
    }

    public static SalterPrincipal orNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SalterPrincipal p ? p : null;
    }

    public static void require(Permission p) {
        if (!get().has(p)) throw ApiException.forbidden("No tienes permiso para realizar esta operación.");
    }
}
