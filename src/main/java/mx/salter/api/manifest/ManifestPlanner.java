package mx.salter.api.manifest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import mx.salter.api.audit.AuditService;
import mx.salter.api.catalog.FolioService;
import mx.salter.api.catalog.SettingsService;
import mx.salter.api.common.ApiException;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideStateMachine;
import mx.salter.api.guide.MovementType;
import mx.salter.api.route.ShippingRoute;
import mx.salter.api.route.ShippingRouteRepository;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.SalterPrincipal;
import mx.salter.api.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organiza los manifiestos por RUTA DE EMBARQUE: al confirmar una guía se incorpora al manifiesto en preparación
 * de su ruta (o se crea uno). Nunca cierra ni despacha manifiestos automáticamente.
 * La búsqueda/creación se serializa bloqueando la fila de la ruta y un índice único parcial garantiza un solo
 * manifiesto en preparación por ruta.
 */
@Component
public class ManifestPlanner {
    private final ManifestRepository manifests;
    private final ManifestGuideRepository manifestGuides;
    private final ShippingRouteRepository routes;
    private final FolioService folios;
    private final UserRepository users;
    private final GuideStateMachine fsm;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public ManifestPlanner(ManifestRepository manifests, ManifestGuideRepository manifestGuides, ShippingRouteRepository routes,
                           FolioService folios, UserRepository users, GuideStateMachine fsm, SettingsService settings,
                           AuditService audit, Clock clock, ZoneId zone) {
        this.manifests = manifests;
        this.manifestGuides = manifestGuides;
        this.routes = routes;
        this.folios = folios;
        this.users = users;
        this.fsm = fsm;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.zone = zone;
    }

    public boolean autoGroupEnabled() {
        return settings.getBool("manifest.autoGroup", true);
    }

    /**
     * Incorpora una guía CONFIRMADA al manifiesto en preparación de su ruta. Sin ruta de embarque, o si ya pertenece
     * a un manifiesto, no hace nada (queda disponible para elaboración manual).
     *
     * @return el manifiesto al que se agregó, si aplica
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Manifest> attach(Guide g) {
        if (!autoGroupEnabled() || g.getShippingRoute() == null) return Optional.empty();
        if (manifestGuides.findByGuideIdAndActiveTrue(g.getId()).isPresent()) return Optional.empty();
        Manifest m = openFor(g.getShippingRoute());
        int pos = manifestGuides.maxPosition(m.getId()) + 1;
        m.getGuides().add(new ManifestGuide(m, g, pos));
        manifests.flush();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("manifest", m.getNumber());
        meta.put("route", g.getShippingRoute().getCode());
        fsm.record(g, MovementType.MANIFIESTO_ASIGNADO, null, null, meta);
        return Optional.of(m);
    }

    /**
     * Retira la guía de su manifiesto EN PREPARACIÓN (p. ej. por cambio de ruta). Si el manifiesto ya está cerrado
     * o despachado no se permite: primero debe reabrirse.
     *
     * @return número del manifiesto del que se retiró, o null si no pertenecía a ninguno
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String detach(Guide g, String reason) {
        ManifestGuide mg = manifestGuides.findByGuideIdAndActiveTrue(g.getId()).orElse(null);
        if (mg == null) return null;
        Manifest m = manifests.lockById(mg.getManifest().getId()).orElseThrow();
        if (m.getStatus() != Manifest.Status.BORRADOR) {
            throw ApiException.business("MANIFEST_CLOSED", "La guía " + g.displayReference() + " está en el manifiesto " + m.getNumber()
                    + " (" + m.getStatus().name().toLowerCase() + "). Reábrelo antes de moverla.");
        }
        m.getGuides().removeIf(x -> x.getGuide().getId().equals(g.getId()));
        manifests.flush();
        fsm.record(g, MovementType.MANIFIESTO_RETIRADA, reason, null, Map.of("manifest", m.getNumber()));
        return m.getNumber();
    }

    /** Manifiesto en preparación de la ruta; si no existe se crea (fecha de hoy, sin turno ni remitente: agrupa por ruta). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Manifest openFor(ShippingRoute route) {
        ShippingRoute locked = routes.lockById(route.getId()).orElseThrow(() -> ApiException.badRequest("INVALID_ROUTE", "Ruta de embarque no válida."));
        return manifests.findOpenByRoute(locked.getId()).orElseGet(() -> {
            SalterPrincipal p = CurrentUser.get();
            Manifest m = new Manifest();
            m.setNumber(folios.next(FolioService.MANIFEST));
            m.setOperationDate(LocalDate.now(clock.withZone(zone)));
            m.setShippingRoute(locked);
            m.setNotes("En preparación para la ruta de embarque " + locked.label() + " (generado automáticamente).");
            m.setCreatedBy(users.getReferenceById(p.userId()));
            manifests.saveAndFlush(m);
            audit.record("MANIFEST_CREATED", "MANIFEST", m.getId(), Map.of("number", m.getNumber(), "route", locked.getCode(), "automatic", true));
            return m;
        });
    }
}
