package utorrent.protocolo;

import java.io.DataOutputStream;

public class MensajeInterested extends MensajePeer {
    public MensajeInterested() { super(IdMensaje.INTERESTED); }
    @Override public int longitudPayload() { return 0; }
    @Override protected void escribirPayload(DataOutputStream salida) { /* sin payload */ }
}