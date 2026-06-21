package utorrent.protocolo;

/**
 * Identificadores de mensajes del protocolo BitTorrent (BEP-3).
 */
public final class IdMensaje {

    public static final byte CHOKE          = 0;
    public static final byte UNCHOKE        = 1;
    public static final byte INTERESTED     = 2;
    public static final byte NOT_INTERESTED = 3;
    public static final byte HAVE           = 4;
    public static final byte BITFIELD       = 5;
    public static final byte REQUEST        = 6;
    public static final byte PIECE          = 7;
    public static final byte CANCEL         = 8;

    private IdMensaje() { /* constantes */ }

    public static String nombre(byte id) {
        switch (id) {
            case CHOKE:          return "choke";
            case UNCHOKE:        return "unchoke";
            case INTERESTED:     return "interested";
            case NOT_INTERESTED: return "not_interested";
            case HAVE:           return "have";
            case BITFIELD:       return "bitfield";
            case REQUEST:        return "request";
            case PIECE:          return "piece";
            case CANCEL:         return "cancel";
            default:             return "desconocido(" + id + ")";
        }
    }
}