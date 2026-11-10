package mx.salter.api;

import static org.assertj.core.api.Assertions.assertThat;

import mx.salter.api.guide.GuideStatus;
import mx.salter.api.recipient.DuplicateDetector;
import mx.salter.api.recipient.DuplicateDetector.Candidate;
import mx.salter.api.recipient.DuplicateDetector.Level;
import mx.salter.api.common.Text;
import org.junit.jupiter.api.Test;

class ReglasDeDominioTest {

    @Test
    void normalizaAcentosYMayusculas() {
        assertThat(Text.normalize("  JOSÉ   Pérez ")).isEqualTo("jose perez");
        assertThat(Text.digits("+52 (55) 1234-5678")).isEqualTo("5512345678");
    }

    @Test
    void duplicadosNoDependenSoloDelNombre() {
        Candidate a = Candidate.of("José", "Pérez", "55 1234 5678", "Reforma 1", "06000");
        assertThat(DuplicateDetector.compare(a, Candidate.of("jose", "perez", "5512345678", null, null))).isEqualTo(Level.EXACT);
        assertThat(DuplicateDetector.compare(a, Candidate.of("Jose", "Perez", null, "reforma 1", "06000"))).isEqualTo(Level.EXACT);
        assertThat(DuplicateDetector.compare(a, Candidate.of("José", "Pérez", "5500000000", "Otra", "01000"))).isEqualTo(Level.PROBABLE);
        assertThat(DuplicateDetector.compare(a, Candidate.of("Ana", "Ruiz", "5512345678", null, null))).isEqualTo(Level.PROBABLE);
        assertThat(DuplicateDetector.compare(a, Candidate.of("Ana", "Ruiz", null, null, null))).isEqualTo(Level.NONE);
    }

    @Test
    void maquinaDeEstadosImpideSaltos() {
        assertThat(GuideStatus.BORRADOR.canTransitionTo(GuideStatus.EN_TRANSITO)).isFalse();
        assertThat(GuideStatus.CONFIRMADA.canTransitionTo(GuideStatus.ENTREGADA)).isFalse();
        assertThat(GuideStatus.LISTA_SALIDA.canTransitionTo(GuideStatus.EN_TRANSITO)).isTrue();
        assertThat(GuideStatus.ENTREGADA.nextStatuses()).isEmpty();
        assertThat(GuideStatus.CANCELADA.nextStatuses()).isEmpty();
    }
}
