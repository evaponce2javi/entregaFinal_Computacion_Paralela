package utorrent.protocolo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Handshake del protocolo BitTorrent (BEP-3).
 */
public class MensajeHandshake {

    public static final int LONGITUD_TOTAL = 68;

    private static final byte[] NOMBRE_PROTOCOLO =
            "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    private static final byte LONGITUD_NOMBRE = 19;

    private final byte[] infoHash;
    private final byte[] peerId;

    public MensajeHandshake(byte[] infoHash, byte[] peerId) {
        if (infoHash == null || infoHash.length != 20)
            throw new IllegalArgumentException("infoHash debe ser de 20 bytes");
        if (peerId == null || peerId.length != 20)
            throw new IllegalArgumentException("peerId debe ser de 20 bytes");
        this.infoHash = infoHash;
        this.peerId = peerId;
    }

    public byte[] getInfoHash() { return infoHash; }
    public byte[] getPeerId()   { return peerId; }

    public String getPeerIdComoString() {
        return new String(peerId, StandardCharsets.US_ASCII);
    }

    public void escribirEn(DataOutputStream salida) throws IOException {
        salida.writeByte(LONGITUD_NOMBRE);          // byte 0
        salida.write(NOMBRE_PROTOCOLO);             // bytes 1-19
        salida.write(new byte[8]);                  // bytes 20-27 reservados
        salida.write(infoHash);                     // bytes 28-47
        salida.write(peerId);                       // bytes 48-67
        salida.flush();
    }

    /**
     * Lee un handshake desde el stream de entrada. Lanza IOException si
     * el formato no es válido (longitud incorrecta o nombre de protocolo
     * distinto al esperado).
     */
    public static MensajeHandshake leerDe(DataInputStream entrada) throws IOException {
        byte longitudNombre = entrada.readByte();
        if (longitudNombre != LONGITUD_NOMBRE) {
            throw new IOException("Handshake inválido: longitud de nombre " + longitudNombre);
        }
        byte[] nombre = new byte[19];
        entrada.readFully(nombre);
        if (!Arrays.equals(nombre, NOMBRE_PROTOCOLO)) {
            throw new IOException("Handshake inválido: protocolo desconocido");
        }
        byte[] reservados = new byte[8];
        entrada.readFully(reservados);

        byte[] infoHash = new byte[20];
        entrada.readFully(infoHash);

        byte[] peerId = new byte[20];
        entrada.readFully(peerId);

        return new MensajeHandshake(infoHash, peerId);
    }
}