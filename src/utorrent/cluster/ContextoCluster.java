package utorrent.cluster;

/**
 * Agrupa los componentes del plano de coordinación del clúster para pasarlos a
 * los manejadores y schedulers del tracker sin inflar sus firmas.
 *
 * Es el punto único de integración: los Pasos 4-6 enchufarán aquí la exclusión
 * mutua (Ricart-Agrawala), el detector de fallos y la elección (Bully).
 */
public final class ContextoCluster {

    private final Membresia membresia;
    private final RelojLamport reloj;
    private final RegistroEventos eventos;
    private final CanalReplica canalReplica;
    private final ExclusionRA exclusionRA;
    private final DetectorFallos detectorFallos;
    private final EleccionBully eleccionBully;

    public ContextoCluster(Membresia membresia, RelojLamport reloj,
                           RegistroEventos eventos, CanalReplica canalReplica,
                           ExclusionRA exclusionRA, DetectorFallos detectorFallos,
                           EleccionBully eleccionBully) {
        this.membresia = membresia;
        this.reloj = reloj;
        this.eventos = eventos;
        this.canalReplica = canalReplica;
        this.exclusionRA = exclusionRA;
        this.detectorFallos = detectorFallos;
        this.eleccionBully = eleccionBully;
    }

    public Membresia getMembresia()         { return membresia; }
    public RelojLamport getReloj()          { return reloj; }
    public RegistroEventos getEventos()     { return eventos; }
    public CanalReplica getCanalReplica()   { return canalReplica; }
    public ExclusionRA getExclusionRA()     { return exclusionRA; }
    public DetectorFallos getDetectorFallos() { return detectorFallos; }
    public EleccionBully getEleccionBully() { return eleccionBully; }
}
