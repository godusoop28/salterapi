package mx.salter.api.common;

import org.springframework.http.HttpStatus;

/** Error de negocio con código estable para el frontend. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public java.util.Map<String, Object> getExtra() { return extra; }

    public ApiException with(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " no encontrado.");
    }
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    public static ApiException business(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
