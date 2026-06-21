package utorrent.protocolo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Permite a otros peers actualizar de forma incremental su mapa de qué piezas 
 * tiene el remitente.
 */
public class MensajeHave extends MensajePeer {

    private final int indicePieza;

    public MensajeHave(int indicePieza) {
        super(IdMensaje.HAVE);
        this.indicePieza = indicePieza;
    }

    public int getIndicePieza() { return indicePieza; }

    @Override
    public int longitudPayload() { return 4; }

    @Override
    protected void escribirPayload(DataOutputStream salida) throws IOException {
        salida.writeInt(indicePieza);
    }

    static MensajeHave leerPayload(DataInputStream entrada) throws IOException {
        int indice = entrada.readInt();
        return new MensajeHave(indice);
    }
}