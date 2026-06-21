package utorrent.modelos;

/**
 * Representa un pedacito de una pieza. BitTorrent los baja de a 16 KB para
 * que podamos pedir partes de una misma pieza a distintos usuarios a la vez.
 */
public class Bloque {

    /** Tamaño típico de un bloque: 16 KB. */
    public static final int TAMANO_ESTANDAR = 16_384;

    private final int indicePieza;
    private final int offset;
    private final byte[] datos;

    public Bloque(int indicePieza, int offset, byte[] datos) {
        this.indicePieza = indicePieza;
        this.offset = offset;
        this.datos = datos;
    }

    public int getIndicePieza() { return indicePieza; }
    public int getOffset()      { return offset; }
    public byte[] getDatos()    { return datos; }
    public int getLongitud()    { return datos.length; }
}