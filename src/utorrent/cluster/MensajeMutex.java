package utorrent.cluster;

import java.io.Serializable;

/**
 * Mensaje del algoritmo de exclusión mutua de Ricart-Agrawala (Paso 4).
 *
 *  REQUEST: el par (selloLamport, nodoOrigen) ES la prioridad de la petición.
 *           Gana el sello menor; a igualdad, el id de nodo menor.
 *  REPLY  : autoriza a entrar a la sección crítica al nodo que pidió.
 *           nodoOrigen identifica a quién responde, para descontarlo de los
 *           pendientes.
 *
 * Viaja por el mismo puerto/marshalling que el resto del tráfico de clúster; el
 * receptor lo distingue por tipo en ManejadorAnuncio.
 */
public final class MensajeMutex implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Tipo { REQUEST, REPLY }

    private final Tipo tipo;
    private final long selloLamport;
    private final int nodoOrigen;

    private MensajeMutex(Tipo tipo, long selloLamport, int nodoOrigen) {
        this.tipo = tipo;
        this.selloLamport = selloLamport;
        this.nodoOrigen = nodoOrigen;
    }

    public static MensajeMutex request(long sello, int origen) {
        return new MensajeMutex(Tipo.REQUEST, sello, origen);
    }

    public static MensajeMutex reply(long sello, int origen) {
        return new MensajeMutex(Tipo.REPLY, sello, origen);
    }

    public Tipo getTipo()         { return tipo; }
    public long getSelloLamport() { return selloLamport; }
    public int getNodoOrigen()    { return nodoOrigen; }
}
