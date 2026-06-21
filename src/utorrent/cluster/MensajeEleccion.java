package utorrent.cluster;

import java.io.Serializable;

/**
 * Mensaje del algoritmo de elección Bully (Paso 6).
 *
 *  ELECTION    : un nodo anuncia que inicia elección (lo envía a los de id mayor).
 *  OK          : un nodo de id mayor confirma que está vivo y toma el relevo,
 *                por lo que el emisor del ELECTION debe retirarse.
 *  COORDINATOR : el ganador (id más alto vivo) se proclama coordinador.
 */
public final class MensajeEleccion implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Tipo { ELECTION, OK, COORDINATOR }

    private final Tipo tipo;
    private final int nodoOrigen;

    private MensajeEleccion(Tipo tipo, int nodoOrigen) {
        this.tipo = tipo;
        this.nodoOrigen = nodoOrigen;
    }

    public static MensajeEleccion election(int origen)    { return new MensajeEleccion(Tipo.ELECTION, origen); }
    public static MensajeEleccion ok(int origen)          { return new MensajeEleccion(Tipo.OK, origen); }
    public static MensajeEleccion coordinator(int origen) { return new MensajeEleccion(Tipo.COORDINATOR, origen); }

    public Tipo getTipo()      { return tipo; }
    public int getNodoOrigen() { return nodoOrigen; }
}
