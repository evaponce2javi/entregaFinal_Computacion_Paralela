package utorrent.protocolo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Respuesta a un MensajeRequest: contiene los bytes del bloque pedido.
 */
public class MensajePiece extends MensajePeer {

    private final int indicePieza;
    private final int begin;
    private final byte[] datos;

    public MensajePiece(int indicePieza, int begin, byte[] datos) {
        super(IdMensaje.PIECE);
        this.indicePieza = indicePieza;
        this.begin = begin;
        this.datos = datos;
    }

    public int getIndicePieza() { return indicePieza; }
    public int getBegin()       { return begin; }
    public byte[] getDatos()    { return datos; }

    @Override
    public int longitudPayload() { return 8 + datos.length; }

    @Override
    protected void escribirPayload(DataOutputStream salida) throws IOException {
        salida.writeInt(indicePieza);
        salida.writeInt(begin);
        salida.write(datos);
    }

    static MensajePiece leerPayload(DataInputStream entrada, int longitudPayload)
            throws IOException {
        int indice = entrada.readInt();
        int begin  = entrada.readInt();
        int longitudDatos = longitudPayload - 8;
        byte[] datos = new byte[longitudDatos];
        entrada.readFully(datos);
        return new MensajePiece(indice, begin, datos);
    }
}