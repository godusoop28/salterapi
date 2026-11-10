package mx.salter.api.user;

import mx.salter.api.common.ApiException;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || password.length() < 10) {
            throw ApiException.badRequest("WEAK_PASSWORD", "La contraseña debe tener al menos 10 caracteres.");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw ApiException.badRequest("WEAK_PASSWORD", "La contraseña debe combinar letras y números.");
        }
    }
}
