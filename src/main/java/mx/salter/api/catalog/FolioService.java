package mx.salter.api.catalog;

import mx.salter.api.catalog.FolioSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folios consecutivos sin huecos: la fila de la secuencia se bloquea (SELECT … FOR UPDATE)
 * dentro de la misma transacción que usa el folio. Si la operación falla, el número no se consume.
 * Formato PROVISIONAL: {PREFIJO}-{NÚMERO con ceros}. La regla oficial está pendiente del cliente.
 */
@Service
public class FolioService {
    public static final String GUIDE = "GUIDE";
    public static final String MANIFEST = "MANIFEST";
    public static final String INCIDENT = "INCIDENT";

    private final FolioSequenceRepository repo;

    public FolioService(FolioSequenceRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String key) {
        FolioSequence seq = repo.lockByKey(key).orElseThrow(() -> new IllegalStateException("Secuencia no configurada: " + key));
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        return seq.getPrefix() + "-" + String.format("%0" + seq.getPadding() + "d", value);
    }

    /**
     * Folio de guía con el formato del cliente: SIGLAS + consecutivo de 9 dígitos (ATM000000001, se imprime
     * "ATM 000000001"). Cada juego de siglas lleva su propio consecutivo, sin huecos.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextGuide(String prefix, int padding) {
        String key = GUIDE + ":" + prefix;
        repo.createIfMissing(key, prefix, padding);
        FolioSequence seq = repo.lockByKey(key).orElseThrow(() -> new IllegalStateException("Secuencia no configurada: " + key));
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        seq.setPadding(padding);
        return seq.getPrefix() + String.format("%0" + seq.getPadding() + "d", value);
    }
}
