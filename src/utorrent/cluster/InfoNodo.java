package utorrent.cluster;

import java.io.Serializable;
import java.util.Objects;

/**
 * Identidad de un nodo del clúster de trackers. No confundir con InfoPar, que
 * describe a un peer del plano de datos P2P: aquí hablamos de los nodos que
 * coordinan el servicio de directorio (función F1).
 *
 * El id es un entero total-ordenado y es la pieza central de la coordinación:
 *  - El algoritmo de elección Bully (Paso 6) elige siempre al id mayor vivo.
 *  - Ricart-Agrawala (Paso 4) desempata la prioridad de la sección crítica
 *    con el par (timestamp Lamport, id).
 *
 * Es Serializable porque viaja dentro de los mensajes inter-tracker (réplica,
 * exclusión mutua y elección) usando el mismo marshalling por ObjectStream que
 * ya emplea el tracker del Parcial.
 */
public final class InfoNodo implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int id;
    private final String host;
    private final int puerto;

    public InfoNodo(int id, String host, int puerto) {
        if (id < 0) throw new IllegalArgumentException("id de nodo debe ser >= 0");
        this.id = id;
        this.host = Objects.requireNonNull(host, "host");
        this.puerto = puerto;
    }

    public int getId()      { return id; }
    public String getHost() { return host; }
    public int getPuerto()  { return puerto; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof InfoNodo)) return false;
        return id == ((InfoNodo) o).id;
    }

    @Override
    public int hashCode() { return Integer.hashCode(id); }

    @Override
    public String toString() {
        return String.format("Nodo#%d@%s:%d", id, host, puerto);
    }
}
