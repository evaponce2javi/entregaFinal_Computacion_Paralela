package utorrent.protocolo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Mapa de bits con las piezas que el remitente posee.
 */
public class MensajeBitfield extends MensajePeer {

    private final byte[] bitfield;

    public MensajeBitfield(byte[] bitfield) {
        super(IdMensaje.BITFIELD);
        this.bitfield = bitfield;
    }

    public byte[] getBitfield() { return bitfield; }

    @Override
    public int longitudPayload() { return bitfield.length; }

    @Override
    protected void escribirPayload(DataOutputStream salida) throws IOException {
        salida.write(bitfield);
    }

    static MensajeBitfield leerPayload(DataInputStream entrada, int longitudPayload)
            throws IOException {
        byte[] bf = new byte[longitudPayload];
        entrada.readFully(bf);
        return new MensajeBitfield(bf);
    }
}