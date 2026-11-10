package mx.salter.api.operation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.salter.api.guide.GuideDtos.GuideDto;
import mx.salter.api.operation.OperationDtos.AssignRequest;
import mx.salter.api.operation.OperationDtos.AssignResult;
import mx.salter.api.operation.OperationDtos.CourierAssignmentDto;
import mx.salter.api.operation.OperationDtos.CourierDto;
import mx.salter.api.operation.OperationDtos.CourierRequest;
import mx.salter.api.operation.OperationDtos.DeliveryRequest;
import mx.salter.api.operation.OperationDtos.HistoryItemDto;
import mx.salter.api.operation.OperationDtos.ReceptionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@Tag(name = "Operación: repartidores, asignaciones, recepción y entrega")
public class OperationController {
    private final CourierService couriers;
    private final AssignmentService assignments;
    private final OperationService operations;

    public OperationController(CourierService couriers, AssignmentService assignments, OperationService operations) {
        this.couriers = couriers;
        this.assignments = assignments;
        this.operations = operations;
    }

    @GetMapping("/couriers")
    @PreAuthorize("hasAnyAuthority('COURIERS_VIEW','COURIERS_MANAGE','ASSIGNMENTS_MANAGE','DISPATCH_AUTHORIZE')")
    public List<CourierDto> couriers(@RequestParam(required = false) Boolean active) {
        return couriers.list(active);
    }

    @PostMapping("/couriers")
    @PreAuthorize("hasAuthority('COURIERS_MANAGE')")
    public ResponseEntity<CourierDto> createCourier(@Valid @RequestBody CourierRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(couriers.create(req));
    }

    @PutMapping("/couriers/{id}")
    @PreAuthorize("hasAuthority('COURIERS_MANAGE')")
    public CourierDto updateCourier(@PathVariable Long id, @Valid @RequestBody CourierRequest req) {
        return couriers.update(id, req);
    }

    @PostMapping("/assignments")
    @PreAuthorize("hasAuthority('ASSIGNMENTS_MANAGE')")
    @Operation(summary = "Asigna o reasigna guías a un repartidor.")
    public AssignResult assign(@Valid @RequestBody AssignRequest req) {
        return new AssignResult(assignments.assign(req.guideIds(), req.courierId()));
    }

    @DeleteMapping("/assignments/guide/{guideId}")
    @PreAuthorize("hasAuthority('ASSIGNMENTS_MANAGE')")
    public ResponseEntity<Void> unassign(@PathVariable Long guideId) {
        assignments.unassign(guideId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/courier/assignments")
    @PreAuthorize("hasAuthority('DELIVERIES_OWN')")
    @Operation(summary = "Envíos asignados al repartidor que inició sesión.")
    public List<CourierAssignmentDto> myAssignments() {
        return operations.myAssignments();
    }

    @GetMapping("/courier/history")
    @PreAuthorize("hasAuthority('DELIVERIES_OWN')")
    public List<HistoryItemDto> myHistory(@RequestParam(defaultValue = "7") int days) {
        return operations.myHistory(days);
    }

    @PostMapping("/guides/{id}/reception")
    @PreAuthorize("hasAuthority('RECEPTION_CONFIRM')")
    @Operation(summary = "Confirma la recepción en destino (una sola vez por guía).")
    public GuideDto receive(@PathVariable Long id, @Valid @RequestBody ReceptionRequest req) {
        return operations.receive(id, req);
    }

    @PostMapping("/guides/{id}/delivery")
    @PreAuthorize("hasAnyAuthority('DELIVERIES_OWN','DELIVERIES_ANY')")
    @Operation(summary = "Confirma la entrega final al destinatario.")
    public GuideDto deliver(@PathVariable Long id, @Valid @RequestBody DeliveryRequest req) {
        return operations.deliver(id, req);
    }
}
