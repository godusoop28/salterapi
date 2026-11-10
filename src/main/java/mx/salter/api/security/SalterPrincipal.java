package mx.salter.api.security;

import java.util.Set;

/**
 * Usuario autenticado, reconstruido desde la base en cada solicitud para que los cambios
 * de permisos o desactivaciones apliquen de inmediato.
 *
 * @param senderId  remitente empresarial asignado (por defecto para capturista; alcance para remitente)
 * @param courierId repartidor vinculado
 */
public record SalterPrincipal(
        Long userId,
        String email,
        String fullName,
        String roleCode,
        Set<String> permissions,
        Long senderId,
        Long courierId) {

    public boolean has(Permission p) {
        return permissions.contains(p.name());
    }

    public boolean hasAny(Permission... ps) {
        for (Permission p : ps) if (has(p)) return true;
        return false;
    }

    /** Usuarios sin acceso a todos los remitentes quedan limitados a su remitente asignado. */
    public boolean restrictedToSender() {
        return !has(Permission.SENDERS_VIEW);
    }
}
