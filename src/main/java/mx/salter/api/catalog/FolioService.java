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
}
