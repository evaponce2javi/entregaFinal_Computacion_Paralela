package utorrent.protocolo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Clase base de todos los mensajes BitTorrent post-handshake.
 * Caso especial: keep-alive es un mensaje sin id ni payload, solo el prefijo
 * de longitud con valor 0. Se modela aparte en la lectura.
 *
 * El método estático leerSiguiente() es el punto de entrada para cualquier
 * receptor: lee la longitud, lee el id, y delega al constructor adecuado.
 */
public abstract class MensajePeer {

    private final byte id;

    protected MensajePeer(byte id) { this.id = id; }

    public byte getId() { return id; }

    public abstract int longitudPayload();

    protected abstract void escribirPayload(DataOutputStream salida) throws IOException;

    /**
     * Escribe el mensaje completo.
     */
    public void escribirEn(DataOutputStream salida) throws IOException {
        int longitud = 1 + longitudPayload();
        salida.writeInt(longitud);
        salida.writeByte(id);
        escribirPayload(salida);
        salida.flush();
    }

    /**
     * Lee el siguiente mensaje del stream.
     */
    public static MensajePeer leerSiguiente(DataInputStream entrada) throws IOException {
        int longitud = entrada.readInt();
        if (longitud < 0) {
            throw new IOException("Longitud de mensaje negativa: " + longitud);
        }
        if (longitud == 0) {
            return null;
        }
        byte id = entrada.readByte();
        int longitudPayload = longitud - 1;

        switch (id) {
            case IdMensaje.CHOKE:          return new MensajeChoke();
            case IdMensaje.UNCHOKE:        return new MensajeUnchoke();
            case IdMensaje.INTERESTED:     return new MensajeInterested();
            case IdMensaje.NOT_INTERESTED: return new MensajeNotInterested();
            case IdMensaje.HAVE:           return MensajeHave.leerPayload(entrada);
            case IdMensaje.BITFIELD:       return MensajeBitfield.leerPayload(entrada, longitudPayload);
            case IdMensaje.REQUEST:        return MensajeRequest.leerPayload(entrada);
            case IdMensaje.PIECE:          return MensajePiece.leerPayload(entrada, longitudPayload);
            default:
                entrada.skipBytes(longitudPayload);
                throw new IOException("ID de mensaje no soportado: " + id);
        }
    }
}