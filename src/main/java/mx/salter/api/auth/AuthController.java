package mx.salter.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import mx.salter.api.auth.AuthDtos.ChangePasswordRequest;
import mx.salter.api.auth.AuthDtos.LoginRequest;
import mx.salter.api.auth.AuthDtos.LoginResponse;
import mx.salter.api.auth.AuthDtos.MeResponse;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.SecurityProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticación")
public class AuthController {
    private final AuthService service;
    private final SecurityProperties props;

    public AuthController(AuthService service, SecurityProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping("/login")
    @Operation(summary = "Inicia sesión. Devuelve el token y además lo guarda en una cookie HttpOnly.")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse res) {
        LoginResponse out = service.login(req.email(), req.password());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(out.token(), Duration.between(Instant.now(), out.expiresAt())).toString());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/logout")
    @Operation(summary = "Cierra la sesión del dispositivo actual (borra la cookie).")
    public ResponseEntity<Void> logout(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Invalida las sesiones del usuario en todos los dispositivos.")
    public ResponseEntity<Void> logoutAll(HttpServletResponse res) {
        service.logoutAll(CurrentUser.get().userId());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return service.me(CurrentUser.get().userId());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletResponse res) {
        service.changePassword(CurrentUser.get().userId(), req.currentPassword(), req.newPassword());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.cookieName(), value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite(props.cookieSameSite())
                .path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }
}
