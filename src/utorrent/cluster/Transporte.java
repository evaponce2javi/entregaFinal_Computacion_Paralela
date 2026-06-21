package utorrent.cluster;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Envío puntual de un objeto serializable a un nodo tracker, con ack que se
 * descarta. Centraliza el patrón de ObjectStream (OOS antes que OIS + flush)
 * que evita el deadlock de cabeceras, y lo comparten el canal de réplica y el
 * mensajero de coordinación.
 */
public final class Transporte {

    private static final int TIMEOUT_MS = 3_000;

    private Transporte() {}

    /** @return true si el objeto se entregó y se leyó el ack sin error. */
    public static boolean enviar(InfoNodo destino, Object msg) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(destino.getHost(), destino.getPuerto()), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(s.getInputStream());
            out.writeObject(msg);
            out.flush();
            in.readObject(); // ack, se descarta
            return true;
        } catch (IOException | ClassNotFoundException e) {
            return false;
        }
    }
}
