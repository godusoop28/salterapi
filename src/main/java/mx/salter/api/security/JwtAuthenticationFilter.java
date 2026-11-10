package mx.salter.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import mx.salter.api.common.GlobalExceptionHandler;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica por cabecera {@code Authorization: Bearer} o por cookie HttpOnly.
 * Protección CSRF: las solicitudes que modifican datos autenticadas por cookie
 * deben incluir la cabecera {@code X-Requested-With}, que un sitio ajeno no puede
 * enviar sin pasar la política CORS restringida.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final JwtService jwt;
    private final PrincipalLoader loader;
    private final SecurityProperties props;
    private final ObjectMapper mapper;

    public JwtAuthenticationFilter(JwtService jwt, PrincipalLoader loader, SecurityProperties props, ObjectMapper mapper) {
        this.jwt = jwt;
        this.loader = loader;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        boolean fromCookie = false;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        } else if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (props.cookieName().equals(c.getName())) {
                    token = c.getValue();
                    fromCookie = true;
                }
            }
        }

        if (token != null && !token.isBlank()) {
            if (fromCookie && !SAFE_METHODS.contains(req.getMethod()) && req.getHeader("X-Requested-With") == null) {
                reject(res, HttpStatus.FORBIDDEN, "CSRF", "Solicitud rechazada: falta la cabecera X-Requested-With.");
                return;
            }
            var principal = jwt.parse(token).flatMap(p -> loader.load(p.userId(), p.tokenVersion()));
            if (principal.isPresent()) {
                SalterPrincipal p = principal.get();
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                p.permissions().forEach(perm -> authorities.add(new SimpleGrantedAuthority(perm)));
                authorities.add(new SimpleGrantedAuthority("ROLE_" + p.roleCode()));
                var auth = new UsernamePasswordAuthenticationToken(p, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put("userId", String.valueOf(p.userId()));
            }
        }
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), GlobalExceptionHandler.problem(status, code, message));
    }
}
