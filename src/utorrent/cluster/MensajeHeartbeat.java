package utorrent.cluster;

import java.io.Serializable;

/**
 * Latido del detector de fallos (Paso 5).
 *
 * Mecanismo de liveness fuera de banda: NO lleva sello de Lamport porque la
 * detección de fallos se basa en tiempo físico (timeouts), ortogonal al orden
 * causal de eventos de la aplicación.
 *
 * La 'secuencia' es el contador de rondas del emisor; permite al receptor:
 *  - detectar OMISIÓN: un hueco hacia adelante (faltan latidos intermedios),
 *  - distinguir un REINICIO del nodo: la secuencia retrocede (vuelve a 0).
 */
public final class MensajeHeartbeat implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int nodoOrigen;
    private final long secuencia;

    public MensajeHeartbeat(int nodoOrigen, long secuencia) {
        this.nodoOrigen = nodoOrigen;
        this.secuencia = secuencia;
    }

    public int getNodoOrigen() { return nodoOrigen; }
    public long getSecuencia() { return secuencia; }
}
