package utorrent.cluster;

import java.io.Serializable;
import utorrent.modelos.InfoPar;
import utorrent.modelos.MetadatosTorrent;

/**
 * Mensaje de replicación de una mutación del directorio (función F1) entre
 * nodos tracker (Paso 3).
 *
 * Viaja por el mismo puerto y el mismo marshalling por ObjectStream que ya usa
 * el tracker para los announce de peers; el receptor lo distingue por tipo en
 * ManejadorAnuncio.
 *
 * Lleva el sello de Lamport del nodo origen para que el receptor avance su
 * reloj (actualizar) y el evento quede ordenado causalmente en el log.
 *
 * El campo nodoOrigen documenta la procedencia: una mutación recibida se aplica
 * con aplicarMutacionRemota() y NO se vuelve a multicast (evita bucles).
 */
public final class MensajeReplica implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Tipo { REGISTRAR_PEER, DESREGISTRAR_PEER, REGISTRAR_METADATOS }

    private final long selloLamport;
    private final int nodoOrigen;
    private final Tipo tipo;
    private final byte[] infoHash;          // REGISTRAR_PEER / DESREGISTRAR_PEER
    private final InfoPar par;              // REGISTRAR_PEER
    private final String peerId;            // DESREGISTRAR_PEER
    private final MetadatosTorrent meta;    // REGISTRAR_METADATOS

    private MensajeReplica(long sello, int origen, Tipo tipo, byte[] infoHash,
                           InfoPar par, String peerId, MetadatosTorrent meta) {
        this.selloLamport = sello;
        this.nodoOrigen = origen;
        this.tipo = tipo;
        this.infoHash = infoHash;
        this.par = par;
        this.peerId = peerId;
        this.meta = meta;
    }

    public static MensajeReplica registrarPeer(long sello, int origen,
                                               byte[] infoHash, InfoPar par) {
        return new MensajeReplica(sello, origen, Tipo.REGISTRAR_PEER, infoHash, par, null, null);
    }

    public static MensajeReplica desregistrarPeer(long sello, int origen,
                                                  byte[] infoHash, String peerId) {
        return new MensajeReplica(sello, origen, Tipo.DESREGISTRAR_PEER, infoHash, null, peerId, null);
    }

    public static MensajeReplica registrarMetadatos(long sello, int origen,
                                                    MetadatosTorrent meta) {
        return new MensajeReplica(sello, origen, Tipo.REGISTRAR_METADATOS, null, null, null, meta);
    }

    public long getSelloLamport()     { return selloLamport; }
    public int getNodoOrigen()        { return nodoOrigen; }
    public Tipo getTipo()             { return tipo; }
    public byte[] getInfoHash()       { return infoHash; }
    public InfoPar getPar()           { return par; }
    public String getPeerId()         { return peerId; }
    public MetadatosTorrent getMeta() { return meta; }
}
