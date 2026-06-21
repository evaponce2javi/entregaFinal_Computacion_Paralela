package utorrent.tracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import utorrent.modelos.InfoPar;
import utorrent.modelos.MetadatosTorrent;
import utorrent.cluster.MensajeReplica;

/**
 * Registro concurrente de peers y torrents conocidos por el Tracker.
 */
public class RegistroPares {

    public static final int MAX_PARES_POR_IP_DEFECTO = 3;
    public static final long VENTANA_MS = 60_000L;

    private final int maxParesPorIp;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, InfoPar>> swarms =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> porNombre = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MetadatosTorrent> metadatos = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> contadorPorIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> inicioVentanaPorIp = new ConcurrentHashMap<>();

    public RegistroPares() { this(MAX_PARES_POR_IP_DEFECTO); }

    public RegistroPares(int maxParesPorIp) {
        if (maxParesPorIp < 1)
            throw new IllegalArgumentException("maxParesPorIp debe ser >= 1");
        this.maxParesPorIp = maxParesPorIp;
    }

    public void registrar(byte[] infoHash, InfoPar par, MetadatosTorrent meta) {
        aplicarRateLimit(par.getDireccionIp());
        registrarInterno(infoHash, par, meta);
    }

    /** Aplica el registro sin rate-limit. Uso interno y para replicación remota. */
    private void registrarInterno(byte[] infoHash, InfoPar par, MetadatosTorrent meta) {
        String hashHex = aHex(infoHash);

        swarms.computeIfAbsent(hashHex, k -> new ConcurrentHashMap<>())
              .put(par.getPeerId(), par);

        if (meta != null) {
            metadatos.putIfAbsent(hashHex, meta);
            porNombre.putIfAbsent(meta.getNombreArchivo(), hashHex);
        }
    }

    /**
     * Aplica una mutación recibida de otro tracker (Paso 3). No vuelve a
     * replicar (evita bucles) ni aplica rate-limit: la IP del InfoPar es la del
     * peer original, ya validada en el nodo de origen.
     */
    public void aplicarMutacionRemota(MensajeReplica msg) {
        switch (msg.getTipo()) {
            case REGISTRAR_PEER:
                registrarInterno(msg.getInfoHash(), msg.getPar(), null);
                break;
            case DESREGISTRAR_PEER:
                desregistrar(msg.getInfoHash(), msg.getPeerId());
                break;
            case REGISTRAR_METADATOS:
                registrarMetadatosSolo(msg.getMeta());
                break;
        }
    }

    /**
     * Registra solo los metadatos, sin tocar el swarm. Lo llama el seeder
     * en su segunda conexión para publicar los hashes y permitir búsqueda
     * por nombre.
     */
    public void registrarMetadatosSolo(MetadatosTorrent meta) {
        if (meta == null) return;
        String hashHex = aHex(meta.getInfoHash());
        metadatos.putIfAbsent(hashHex, meta);
        porNombre.putIfAbsent(meta.getNombreArchivo(), hashHex);
    }

    public void desregistrar(byte[] infoHash, String peerId) {
        String hashHex = aHex(infoHash);
        Map<String, InfoPar> swarm = swarms.get(hashHex);
        if (swarm != null) swarm.remove(peerId);
    }

    public List<InfoPar> obtenerPares(byte[] infoHash, String peerIdExcluir) {
        String hashHex = aHex(infoHash);
        Map<String, InfoPar> swarm = swarms.get(hashHex);
        if (swarm == null) return Collections.emptyList();
        List<InfoPar> resultado = new ArrayList<>();
        for (InfoPar p : swarm.values()) {
            if (!p.getPeerId().equals(peerIdExcluir)) resultado.add(p);
        }
        return resultado;
    }

    public byte[] resolverInfoHashPorNombre(String nombreArchivo) {
        String hashHex = porNombre.get(nombreArchivo);
        if (hashHex == null) return null;
        return deHex(hashHex);
    }

    public MetadatosTorrent obtenerMetadatos(byte[] infoHash) {
        return metadatos.get(aHex(infoHash));
    }

    public int contarSwarms() { return swarms.size(); }

    public int contarPares(byte[] infoHash) {
        Map<String, InfoPar> swarm = swarms.get(aHex(infoHash));
        return swarm == null ? 0 : swarm.size();
    }

    public void reiniciarVentana() {
        contadorPorIp.clear();
        inicioVentanaPorIp.clear();
    }

    private synchronized void aplicarRateLimit(String ip) {
        long ahora = System.currentTimeMillis();
        long inicio = inicioVentanaPorIp.getOrDefault(ip, 0L);
        if (ahora - inicio > VENTANA_MS) {
            inicioVentanaPorIp.put(ip, ahora);
            contadorPorIp.put(ip, new AtomicInteger(0));
        }
        int actual = contadorPorIp
                .computeIfAbsent(ip, k -> new AtomicInteger(0))
                .incrementAndGet();
        if (actual > maxParesPorIp) {
            throw new SecurityException(String.format(
                    "Rate limit excedido para IP %s (%d > %d)", ip, actual, maxParesPorIp));
        }
    }

    private static String aHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] deHex(String hex) {
        byte[] resultado = new byte[hex.length() / 2];
        for (int i = 0; i < resultado.length; i++) {
            resultado[i] = (byte) Integer.parseInt(
                    hex.substring(i * 2, i * 2 + 2), 16);
        }
        return resultado;
    }
}