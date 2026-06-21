package utorrent.cluster;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Emisor de la replicación del directorio entre trackers (Paso 3).
 *
 * Cuando un nodo aplica localmente una mutación de F1 (alta/baja de peer o
 * publicación de metadatos), hace un multicast best-effort de esa mutación al
 * resto de nodos VIVOS. El envío es asíncrono (pool dedicado) para no bloquear
 * la respuesta del announce del peer.
 *
 * Best-effort: si una réplica está caída, el fallo se ignora aquí — de eso se
 * encargará el detector de fallos (Paso 5). La consistencia fuerte del ORDEN de
 * escrituras la aportará la exclusión mutua de Ricart-Agrawala (Paso 4); por
 * ahora el directorio es de consistencia eventual (read-your-writes no
 * garantizado si un peer escribe en un nodo y lee en otro de inmediato).
 *
 * mensajesEnviados cuenta los mensajes de coordinación que salen al cable,
 * una de las métricas que pide la rúbrica (req. 3.2).
 */
public final class CanalReplica {

    private static final int TIMEOUT_MS = 3_000;

    private final Membresia membresia;
    private final ExecutorService poolEnvio;
    private final LongAdder mensajesEnviados = new LongAdder();

    public CanalReplica(Membresia membresia) {
        this.membresia = membresia;
        AtomicInteger n = new AtomicInteger(1);
        this.poolEnvio = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "replica-envio-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    /** Multicast best-effort de una mutación local a los demás trackers vivos. */
    public void replicar(MensajeReplica msg) {
        List<Integer> pares = membresia.paresAlcanzables();
        for (int id : pares) {
            InfoNodo destino = membresia.infoDe(id);
            if (destino == null) continue;
            poolEnvio.submit(() -> enviarA(destino, msg));
        }
    }

    private void enviarA(InfoNodo destino, MensajeReplica msg) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(destino.getHost(), destino.getPuerto()), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            // OOS antes que OIS y flush inmediato: evita el deadlock de cabeceras
            // de ObjectStream (mismo patrón que ClienteTracker).
            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(s.getInputStream());
            out.writeObject(msg);
            out.flush();
            in.readObject(); // ack, se descarta
            mensajesEnviados.increment();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Replica] No pude replicar a " + destino + ": " + e.getMessage());
        }
    }

    public long mensajesEnviados() { return mensajesEnviados.sum(); }

    public void detener() { poolEnvio.shutdownNow(); }
}
