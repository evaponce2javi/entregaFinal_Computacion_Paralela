package utorrent.modelos;

import java.io.Serializable;
import java.util.List;

/**
 * Es básicamente el archivo .torrent. Tiene toda la info necesaria 
 * para saber qué estamos bajando y cómo validarlo.
 */
public class MetadatosTorrent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nombreArchivo;
    private final long longitudTotal;
    private final int longitudPieza;
    private final byte[] infoHash;          // 20 bytes
    private final List<byte[]> hashesDePiezas; // cada uno 20 bytes
    private final String ipTracker;
    private final int puertoTracker;

    public MetadatosTorrent(String nombreArchivo, long longitudTotal, int longitudPieza,
                            byte[] infoHash, List<byte[]> hashesDePiezas,
                            String ipTracker, int puertoTracker) {
        this.nombreArchivo = nombreArchivo;
        this.longitudTotal = longitudTotal;
        this.longitudPieza = longitudPieza;
        this.infoHash = infoHash;
        this.hashesDePiezas = hashesDePiezas;
        this.ipTracker = ipTracker;
        this.puertoTracker = puertoTracker;
    }

    public String getNombreArchivo()       { return nombreArchivo; }
    public long getLongitudTotal()         { return longitudTotal; }
    public int getLongitudPieza()          { return longitudPieza; }
    public byte[] getInfoHash()            { return infoHash; }
    public List<byte[]> getHashesDePiezas(){ return hashesDePiezas; }
    public String getIpTracker()           { return ipTracker; }
    public int getPuertoTracker()          { return puertoTracker; }

    /** Cantidad total de piezas en que se divide el archivo. */
    public int totalPiezas() {
        return hashesDePiezas.size();
    }

    /**
     * Calcula cuánto mide una pieza. 
     * Ojo con la última, que suele ser más chica.
     */
    public int longitudPiezaEn(int indice) {
        if (indice == totalPiezas() - 1) {
            long resto = longitudTotal % longitudPieza;
            return resto == 0 ? longitudPieza : (int) resto;
        }
        return longitudPieza;
    }
}