package utorrent.tracker;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import utorrent.modelos.MetadatosTorrent;
import utorrent.modelos.RespuestaAnuncio;
import utorrent.modelos.SolicitudAnuncio;

/**
 * Cliente del Tracker con failover sobre el clúster de trackers (Paso 7).
 *
 * Recibe una LISTA de endpoints (los nodos del clúster). Cada operación intenta
 * el endpoint "preferido" y, ante un fallo de conexión/E-S, rota FAIL-FAST al
 * siguiente sin agotar el backoff sobre un nodo caído. En cuanto un endpoint
 * responde, queda como preferido (failover sticky). Mientras quede >=1 tracker
 * vivo, el announce/consulta tiene éxito: el descubrimiento sobrevive a la caída
 * de un nodo (cierra R1/R4 desde el plano de datos).
 *
 * El backoff de reintentos (5/15/30 s) se conserva, pero ahora envuelve una
 * ronda completa por TODOS los endpoints: solo se espera y reintenta si todos
 * los trackers fallaron.
 */
public class ClienteTracker {

    private static final int TIMEOUT_CONEXION_MS = 5_000;
    private static final int TIMEOUT_LECTURA_MS = 10_000;
    private static final long[] BACKOFF_MS = {5_000L, 15_000L, 30_000L};

    private final List<InetSocketAddress> endpoints;
    private final AtomicInteger indicePreferido = new AtomicInteger(0);

    /** Constructor de un solo tracker (compatibilidad con el Parcial). */
    public ClienteTracker(String ipTracker, int puertoTracker) {
        this(List.of(new InetSocketAddress(ipTracker, puertoTracker)));
    }

    /** Constructor de clúster: failover sobre la lista de endpoints. */
    public ClienteTracker(List<InetSocketAddress> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un endpoint de tracker");
        }
        this.endpoints = new ArrayList<>(endpoints);
    }

    public RespuestaAnuncio anunciar(byte[] infoHash, String peerId, int puertoEscucha,
                                     long subido, long descargado, long restante,
                                     String evento) {
        SolicitudAnuncio solicitud = new SolicitudAnuncio(
                infoHash, peerId, puertoEscucha,
                subido, descargado, restante, evento, null);
        return enviarConReintentos(solicitud, "anunciar(" + evento + ")");
    }

    /** Announce inicial de un Seeder seguido de la publicación de metadatos. */
    public RespuestaAnuncio publicarSeed(MetadatosTorrent meta, String peerId,
                                         int puertoEscucha) {
        RespuestaAnuncio r1 = anunciar(meta.getInfoHash(), peerId, puertoEscucha,
                0, meta.getLongitudTotal(), 0, "iniciado");
        if (r1 == null || !r1.isExito()) return r1;
        return enviarConReintentos(meta, "publicarMetadatos");
    }

    public RespuestaAnuncio consultarPorNombre(String nombreArchivo, String peerId) {
        SolicitudAnuncio consulta = new SolicitudAnuncio(
                new byte[0], peerId, 0,
                0, 0, 0, "consulta", nombreArchivo);
        return enviarConReintentos(consulta, "consultarPorNombre(" + nombreArchivo + ")");
    }

    public void desconectar(byte[] infoHash, String peerId, int puertoEscucha) {
        SolicitudAnuncio detenido = new SolicitudAnuncio(
                infoHash, peerId, puertoEscucha,
                0, 0, 0, "detenido", null);
        intentarConFailover(detenido);   // una ronda, sin backoff
    }

    /**
     * Backoff de reintentos (5/15/30 s) que envuelve una ronda completa de
     * failover. Solo espera y reintenta si TODOS los trackers fallaron.
     */
    private RespuestaAnuncio enviarConReintentos(Object mensaje, String operacion) {
        for (int intento = 0; intento < BACKOFF_MS.length; intento++) {
            try {
                if (intento > 0) {
                    long espera = BACKOFF_MS[intento - 1];
                    System.out.printf("[ClienteTracker] %s: todos los trackers fallaron, "
                            + "espero %d ms antes del intento %d%n", operacion, espera, intento + 1);
                    Thread.sleep(espera);
                }
                RespuestaAnuncio resp = intentarConFailover(mensaje);
                if (resp != null) return resp;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        System.err.printf("[ClienteTracker] %s: agotados los reintentos en todo el clúster.%n", operacion);
        return null;
    }

    /** Rota FAIL-FAST por todos los endpoints; devuelve la primera respuesta exitosa. */
    private RespuestaAnuncio intentarConFailover(Object mensaje) {
        int n = endpoints.size();
        int inicio = indicePreferido.get();
        for (int i = 0; i < n; i++) {
            int idx = (inicio + i) % n;
            InetSocketAddress ep = endpoints.get(idx);
            RespuestaAnuncio r = intentarUnaVez(ep, mensaje);
            if (r != null) {
                indicePreferido.set(idx);   // sticky: prefiere el que respondió
                if (i > 0) System.out.printf("[ClienteTracker] failover -> %s%n", ep);
                return r;
            }
        }
        return null;   // todos fallaron -> el backoff reintentará la ronda
    }

    private RespuestaAnuncio intentarUnaVez(InetSocketAddress ep, Object mensaje) {
        try (Socket socket = new Socket()) {
            socket.connect(ep, TIMEOUT_CONEXION_MS);
            socket.setSoTimeout(TIMEOUT_LECTURA_MS);

            ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream());
            salida.flush();
            ObjectInputStream entrada = new ObjectInputStream(socket.getInputStream());

            salida.writeObject(mensaje);
            salida.flush();

            Object respuesta = entrada.readObject();
            if (respuesta instanceof RespuestaAnuncio) {
                return (RespuestaAnuncio) respuesta;
            }
            return null;
        } catch (IOException | ClassNotFoundException e) {
            System.err.printf("[ClienteTracker] fallo con %s -> %s%n", ep, e.getMessage());
            return null;
        }
    }

    @Override
    public String toString() { return endpoints.toString(); }
}
