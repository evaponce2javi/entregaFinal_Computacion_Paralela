package utorrent.tracker;

import utorrent.modelos.InfoPar;
import utorrent.modelos.MetadatosTorrent;
import utorrent.modelos.RespuestaAnuncio;
import utorrent.modelos.SolicitudAnuncio;
import utorrent.cluster.ContextoCluster;
import utorrent.cluster.MensajeEleccion;
import utorrent.cluster.MensajeHeartbeat;
import utorrent.cluster.MensajeMutex;
import utorrent.cluster.MensajeReplica;
import utorrent.cluster.TipoEvento;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;

/**
 * Maneja una única conexión entrante al Tracker. Se ejecuta en un hilo del
 * ExecutorService del ServidorTracker, cumpliendo el patrón de "hilo por cliente".
 */
public class ManejadorAnuncio implements Runnable {

    private static final int INTERVALO_ANUNCIO_S = 60;

    private final Socket socket;
    private final RegistroPares registro;
    private final ContextoCluster ctx;

    public ManejadorAnuncio(Socket socket, RegistroPares registro, ContextoCluster ctx) {
        this.socket = socket;
        this.registro = registro;
        this.ctx = ctx;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(10_000);
        } catch (IOException ignorada) {}

        try (ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream())) {
            salida.flush();
            try (ObjectInputStream entrada = new ObjectInputStream(socket.getInputStream())) {

                Object mensaje = entrada.readObject();

                if (mensaje instanceof MensajeReplica) {
                    manejarReplica((MensajeReplica) mensaje, salida);
                    return;
                }

                if (mensaje instanceof MensajeMutex) {
                    ctx.getExclusionRA().recibir((MensajeMutex) mensaje);
                    salida.writeObject(new RespuestaAnuncio(
                            true, "ack-mutex", INTERVALO_ANUNCIO_S,
                            java.util.Collections.emptyList(), null));
                    return;
                }

                if (mensaje instanceof MensajeHeartbeat) {
                    ctx.getDetectorFallos().recibir((MensajeHeartbeat) mensaje);
                    salida.writeObject(new RespuestaAnuncio(
                            true, "ack-hb", INTERVALO_ANUNCIO_S,
                            java.util.Collections.emptyList(), null));
                    return;
                }

                if (mensaje instanceof MensajeEleccion) {
                    ctx.getEleccionBully().recibir((MensajeEleccion) mensaje);
                    salida.writeObject(new RespuestaAnuncio(
                            true, "ack-eleccion", INTERVALO_ANUNCIO_S,
                            java.util.Collections.emptyList(), null));
                    return;
                }

                if (mensaje instanceof MetadatosTorrent) {
                    manejarPublicacionMetadatos((MetadatosTorrent) mensaje, salida);
                    return;
                }

                if (!(mensaje instanceof SolicitudAnuncio)) {
                    salida.writeObject(new RespuestaAnuncio(
                            false, "Tipo de mensaje no válido", 0, null, null));
                    return;
                }

                SolicitudAnuncio solicitud = (SolicitudAnuncio) mensaje;
                String ipRemota = socket.getInetAddress().getHostAddress();

                if (esConsultaPorNombre(solicitud)) {
                    manejarConsultaPorNombre(solicitud, salida);
                    return;
                }

                manejarAnuncioEstandar(solicitud, ipRemota, salida);
            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Tracker] Error en manejador: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignorada) {}
        }
    }

    private boolean esConsultaPorNombre(SolicitudAnuncio s) {
        return (s.getInfoHash() == null || s.getInfoHash().length == 0)
                && s.getNombreArchivo() != null
                && "consulta".equals(s.getEvento());
    }

    private void manejarConsultaPorNombre(SolicitudAnuncio solicitud,
                                          ObjectOutputStream salida) throws IOException {
        byte[] infoHash = registro.resolverInfoHashPorNombre(solicitud.getNombreArchivo());
        if (infoHash == null) {
            salida.writeObject(new RespuestaAnuncio(
                    false, "Archivo no encontrado: " + solicitud.getNombreArchivo(),
                    0, null, null));
            return;
        }
        MetadatosTorrent meta = registro.obtenerMetadatos(infoHash);
        List<InfoPar> pares = registro.obtenerPares(infoHash, solicitud.getPeerId());
        salida.writeObject(new RespuestaAnuncio(
                true, "OK", INTERVALO_ANUNCIO_S, pares, meta));
        System.out.printf("[Tracker] consulta-nombre archivo=%s pares_devueltos=%d%n",
                solicitud.getNombreArchivo(), pares.size());
    }

    private void manejarAnuncioEstandar(SolicitudAnuncio solicitud, String ipRemota,
                                        ObjectOutputStream salida) throws IOException {
        InfoPar par = new InfoPar(
                solicitud.getPeerId(), ipRemota, solicitud.getPuertoEscucha());

        try {
            ctx.getExclusionRA().entrarSC();
            try {
                if ("detenido".equals(solicitud.getEvento())) {
                    registro.desregistrar(solicitud.getInfoHash(), solicitud.getPeerId());
                    replicarLocal(MensajeReplica.desregistrarPeer(
                            ctx.getReloj().tick(), ctx.getMembresia().idLocal(),
                            solicitud.getInfoHash(), solicitud.getPeerId()));
                } else {
                    registro.registrar(solicitud.getInfoHash(), par, null);
                    replicarLocal(MensajeReplica.registrarPeer(
                            ctx.getReloj().tick(), ctx.getMembresia().idLocal(),
                            solicitud.getInfoHash(), par));
                }
            } finally {
                ctx.getExclusionRA().salirSC();
            }

            List<InfoPar> pares = registro.obtenerPares(
                    solicitud.getInfoHash(), solicitud.getPeerId());

            salida.writeObject(new RespuestaAnuncio(
                    true, "OK", INTERVALO_ANUNCIO_S, pares, null));

            System.out.printf("[Tracker] %-12s peer=%s ip=%s pares_swarm=%d%n",
                    solicitud.getEvento(),
                    solicitud.getPeerId().substring(0, Math.min(8, solicitud.getPeerId().length())),
                    ipRemota, pares.size() + 1);

        } catch (SecurityException se) {
            salida.writeObject(new RespuestaAnuncio(
                    false, se.getMessage(), 0, null, null));
            System.err.println("[Tracker] Anti-Sybil bloqueó: " + se.getMessage());
        }
    }

    private void manejarPublicacionMetadatos(MetadatosTorrent meta,
                                             ObjectOutputStream salida) throws IOException {
        String ipRemota = socket.getInetAddress().getHostAddress();
        ctx.getExclusionRA().entrarSC();
        try {
            registro.registrarMetadatosSolo(meta);
            replicarLocal(MensajeReplica.registrarMetadatos(
                    ctx.getReloj().tick(), ctx.getMembresia().idLocal(), meta));
        } finally {
            ctx.getExclusionRA().salirSC();
        }

        salida.writeObject(new RespuestaAnuncio(
                true, "Metadatos publicados", INTERVALO_ANUNCIO_S,
                java.util.Collections.emptyList(), meta));

        System.out.printf("[Tracker] metadatos publicados archivo=%s ip=%s%n",
                meta.getNombreArchivo(), ipRemota);
    }

    /**
     * Registra la escritura local de F1 con su sello de Lamport y dispara el
     * multicast de replicación a los demás trackers (best-effort, asíncrono).
     */
    private void replicarLocal(MensajeReplica msg) {
        ctx.getEventos().registrar(msg.getSelloLamport(), TipoEvento.DIRECTORIO_ESCRITURA,
                "origen-local " + msg.getTipo());
        ctx.getCanalReplica().replicar(msg);
    }

    /**
     * Aplica una mutación recibida de otro tracker: avanza el reloj con el
     * sello recibido, aplica al registro local (sin re-replicar) y responde ack.
     */
    private void manejarReplica(MensajeReplica msg, ObjectOutputStream salida) throws IOException {
        long ts = ctx.getReloj().actualizar(msg.getSelloLamport());
        registro.aplicarMutacionRemota(msg);
        ctx.getEventos().registrar(ts, TipoEvento.REPLICA_RECEPCION,
                "de nodo " + msg.getNodoOrigen() + " tipo=" + msg.getTipo());
        salida.writeObject(new RespuestaAnuncio(
                true, "Replica aplicada", INTERVALO_ANUNCIO_S,
                java.util.Collections.emptyList(), null));
    }
}