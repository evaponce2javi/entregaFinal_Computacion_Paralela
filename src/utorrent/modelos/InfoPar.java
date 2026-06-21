package utorrent.modelos;

import java.io.Serializable;
import java.util.Objects;

/**
 * Datos básicos para conectarse a otro usuario (peer) en la red.
 */
public class InfoPar implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String peerId; //ID unico de 20 bytes del cliente
    private final String direccionIp; // su IP actual
    private final int puerto; // Puerto donde escucha conexiones

    public InfoPar(String peerId, String direccionIp, int puerto) {
        this.peerId = peerId;
        this.direccionIp = direccionIp;
        this.puerto = puerto;
    }

    public String getPeerId()       { return peerId; }
    public String getDireccionIp()  { return direccionIp; }
    public int getPuerto()          { return puerto; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof InfoPar)) return false;
        InfoPar otro = (InfoPar) o;
        return Objects.equals(peerId, otro.peerId);
    }

    @Override
    public int hashCode() { return Objects.hash(peerId); }

    @Override
    public String toString() {
        return String.format("Par[%s @ %s:%d]",
                peerId.substring(0, Math.min(8, peerId.length())),
                direccionIp, puerto);
    }
}