package utorrent.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Vista concurrente de la membresía del clúster de trackers.
 *
 * Para cada nodo conocido guarda su InfoNodo (identidad/ubicación) y su
 * EstadoNodo (vivo/sospechoso/caído). Es la estructura base sobre la que
 * operarán el detector de fallos (Paso 5), Ricart-Agrawala (Paso 4) y la
 * elección Bully (Paso 6).
 *
 * Concurrencia: las lecturas son lock-free (ConcurrentHashMap). Las
 * transiciones de estado se serializan (synchronized) para que la
 * notificación a los oyentes sea coherente y no se entrelace entre hilos.
 *
 * Al construirse, el nodo local queda VIVO y el resto SOSPECHOSO: durante el
 * periodo de gracia inicial nadie se declara caído hasta el primer heartbeat.
 */
public final class Membresia {

    private final int idLocal;
    private final ConcurrentHashMap<Integer, InfoNodo> nodos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, EstadoNodo> estados = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<OyenteMembresia> oyentes = new CopyOnWriteArrayList<>();

    public Membresia(InfoNodo local, List<InfoNodo> iniciales) {
        this.idLocal = local.getId();
        nodos.put(local.getId(), local);
        estados.put(local.getId(), EstadoNodo.VIVO);
        for (InfoNodo n : iniciales) {
            if (n.getId() == idLocal) continue;
            nodos.put(n.getId(), n);
            estados.put(n.getId(), EstadoNodo.SOSPECHOSO);
        }
    }

    public int idLocal()        { return idLocal; }
    public InfoNodo local()     { return nodos.get(idLocal); }
    public InfoNodo infoDe(int id) { return nodos.get(id); }

    public EstadoNodo estado(int id) {
        return estados.getOrDefault(id, EstadoNodo.CAIDO);
    }

    public void registrarOyente(OyenteMembresia o) { oyentes.add(o); }

    /**
     * Cambia el estado de un nodo y notifica a los oyentes solo si hubo un
     * cambio real. Se serializa para que las notificaciones lleguen ordenadas.
     */
    public synchronized void transicionar(int id, EstadoNodo nuevo) {
        EstadoNodo previo = estados.put(id, nuevo);
        if (previo == null) previo = EstadoNodo.CAIDO;
        if (previo != nuevo) {
            for (OyenteMembresia o : oyentes) {
                o.onCambioEstado(id, previo, nuevo);
            }
        }
    }

    /** Ids de todos los nodos conocidos, en orden total ascendente. */
    public List<Integer> idsOrdenados() {
        List<Integer> ids = new ArrayList<>(nodos.keySet());
        Collections.sort(ids);
        return ids;
    }

    /** Ids VIVOS distintos del local (destinatarios de un multicast normal). */
    public List<Integer> paresVivos() {
        List<Integer> r = new ArrayList<>();
        for (Integer id : idsOrdenados()) {
            if (id != idLocal && estados.get(id) == EstadoNodo.VIVO) r.add(id);
        }
        return r;
    }

    /** Ids VIVOS incluido el local (define el quórum de Ricart-Agrawala). */
    public List<Integer> vivos() {
        List<Integer> r = new ArrayList<>();
        for (Integer id : idsOrdenados()) {
            if (estados.get(id) == EstadoNodo.VIVO) r.add(id);
        }
        return r;
    }

    /** Nodos VIVOS con id mayor al local: objetivo de los ELECTION de Bully. */
    public List<Integer> vivosConIdMayor() {
        List<Integer> r = new ArrayList<>();
        for (Integer id : idsOrdenados()) {
            if (id > idLocal && estados.get(id) == EstadoNodo.VIVO) r.add(id);
        }
        return r;
    }

    /** Nodos NO-CAIDO con id mayor al local: a quienes Bully envía ELECTION. */
    public List<Integer> alcanzablesConIdMayor() {
        List<Integer> r = new ArrayList<>();
        for (Integer id : idsOrdenados()) {
            if (id > idLocal && estados.get(id) != EstadoNodo.CAIDO) r.add(id);
        }
        return r;
    }

    /** Id más alto conocido (coordinador determinista de arranque). */
    public int idMaximo() {
        int m = idLocal;
        for (Integer id : nodos.keySet()) if (id > m) m = id;
        return m;
    }

    /**
     * Ids distintos del local que NO están CAIDO (VIVO o SOSPECHOSO). Es el
     * objetivo de la replicación best-effort: durante el periodo de gracia los
     * pares siguen SOSPECHOSO, pero probablemente están arriba, así que se les
     * replica igual; si en realidad están caídos, el timeout del socket lo cubre
     * y el detector de fallos (Paso 5) terminará marcándolos CAIDO.
     */
    public List<Integer> paresAlcanzables() {
        List<Integer> r = new ArrayList<>();
        for (Integer id : idsOrdenados()) {
            if (id != idLocal && estados.get(id) != EstadoNodo.CAIDO) r.add(id);
        }
        return r;
    }

    /** Ids de todos los pares conocidos (distintos del local), sin importar su estado. */
    public List<Integer> paresTotales() {
        List<Integer> r = new ArrayList<>();
        for (Integer id : idsOrdenados()) {
            if (id != idLocal) r.add(id);
        }
        return r;
    }

    public int tamano() { return nodos.size(); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Membresia[local=").append(idLocal).append(" | ");
        for (Integer id : idsOrdenados()) {
            sb.append(id).append(':').append(estados.get(id)).append(' ');
        }
        return sb.append(']').toString();
    }
}
