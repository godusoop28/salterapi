package mx.salter.api.route;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.salter.api.route.RouteDtos.RouteDto;
import mx.salter.api.route.RouteDtos.RouteOverview;
import mx.salter.api.route.RouteDtos.RouteRequest;
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
@RequestMapping("/api/shipping-routes")
@Tag(name = "Rutas de embarque")
public class RouteController {
    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Catálogo de rutas de embarque (clave y nombre). La captura de guías lo usa para proponer la ruta.")
    public List<RouteDto> list(@RequestParam(required = false) Boolean active) {
        return service.list(active);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('ROUTES_VIEW','ROUTES_MANAGE')")
    @Operation(summary = "Por ruta: manifiesto en preparación y guías vigentes por estado.")
    public List<RouteOverview> overview() {
        return service.overview();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROUTES_MANAGE')")
    public ResponseEntity<RouteDto> create(@Valid @RequestBody RouteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROUTES_MANAGE')")
    public RouteDto update(@PathVariable Long id, @Valid @RequestBody RouteRequest req) {
        return service.update(id, req);
    }
}
