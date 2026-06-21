package utorrent.app;

import utorrent.modelos.InfoPar;
import utorrent.modelos.MetadatosTorrent;
import utorrent.modelos.RespuestaAnuncio;
import utorrent.p2p.ClientePar;
import utorrent.p2p.EnsambladorPiezas;
import utorrent.p2p.GestorChoke;
import utorrent.p2p.GestorPiezas;
import utorrent.p2p.ServidorPar;
import utorrent.tracker.ClienteTracker;
import utorrent.utils.EscritorBloques;
import utorrent.utils.LectorBloques;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinador de una sesión torrent completa, sea como seeder o como leecher.
 */
public class SesionTorrent {

    public enum Modo { SEEDER, LEECHER }

    private final MetadatosTorrent meta;
    private final String miPeerId;
    private final int puertoEscucha;
    private final Modo modo;
    private final ClienteTracker clienteTracker;
    private final LectorBloques lectorBloques;
    private final EscritorBloques escritorBloques;

    private GestorPiezas gestorPiezas;
    private EnsambladorPiezas ensamblador;
    private GestorChoke gestorChoke;
    private ServidorPar servidorPar;
    private ClientePar clientePar;

    private final ExecutorService poolSesionesP2P;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean ejecutando = new AtomicBoolean(false);
    private final AtomicBoolean anuncioCompletadoEnviado = new AtomicBoolean(false);

    public SesionTorrent(MetadatosTorrent meta, String miPeerId, int puertoEscucha,
                         Modo modo, ClienteTracker clienteTracker,
                         LectorBloques lector, EscritorBloques escritor) {
        this.meta = meta;
        this.miPeerId = miPeerId;
        this.puertoEscucha = puertoEscucha;
        this.modo = modo;
        this.clienteTracker = clienteTracker;
        this.lectorBloques = lector;
        this.escritorBloques = escritor;

        AtomicInteger n = new AtomicInteger(1);
        this.poolSesionesP2P = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "p2p-saliente-" + n.getAndIncrement());
            t.setDaemon(false);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "torrent-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void iniciar() throws IOException {
        ejecutando.set(true);

        boolean yaCompleto = (modo == Modo.SEEDER);
        gestorPiezas = new GestorPiezas(meta, yaCompleto);
        ensamblador = (escritorBloques != null)
                ? new EnsambladorPiezas(gestorPiezas, escritorBloques)
                : null;
        gestorChoke = new GestorChoke();
        gestorChoke.iniciar();

        servidorPar = new ServidorPar(puertoEscucha, meta, miPeerId,
                gestorPiezas, ensamblador, gestorChoke, lectorBloques);
        servidorPar.iniciar();

        clientePar = new ClientePar(meta, miPeerId,
                gestorPiezas, ensamblador, gestorChoke, lectorBloques,
                servidorPar, poolSesionesP2P);

        long restante = (modo == Modo.SEEDER) ? 0 : meta.getLongitudTotal();
        RespuestaAnuncio respuesta = clienteTracker.anunciar(
                meta.getInfoHash(), miPeerId, puertoEscucha,
                0, 0, restante, "iniciado");

        if (respuesta != null && respuesta.isExito()) {
            conectarAPeers(respuesta.getPares());
        } else {
            System.err.println("[SesionTorrent] El tracker no respondió. " +
                    "El sistema seguirá operando con peers entrantes solamente.");
        }

        int intervalo = (respuesta != null) ? Math.max(30, respuesta.getIntervalo()) : 60;
        scheduler.scheduleAtFixedRate(this::anuncioPeriodico,
                intervalo, intervalo, TimeUnit.SECONDS);

        if (modo == Modo.LEECHER) {
            scheduler.scheduleAtFixedRate(this::verificarCompletado,
                    2, 2, TimeUnit.SECONDS);
        }
    }

    private void conectarAPeers(List<InfoPar> pares) {
        if (pares == null || pares.isEmpty()) {
            System.out.println("[SesionTorrent] El swarm aún no tiene otros peers. Esperando...");
            return;
        }
        System.out.println("[SesionTorrent] Conectando a " + pares.size() + " peer(s)...");
        for (InfoPar par : pares) {
            clientePar.conectarA(par);
        }
    }

    private void anuncioPeriodico() {
        if (!ejecutando.get()) return;
        long descargado = (long) gestorPiezas.piezasCompletadas() * meta.getLongitudPieza();
        long restante = Math.max(0, meta.getLongitudTotal() - descargado);
        RespuestaAnuncio resp = clienteTracker.anunciar(
                meta.getInfoHash(), miPeerId, puertoEscucha,
                0, descargado, restante, "actualizado");
        if (resp != null && resp.isExito()) {
            conectarAPeers(resp.getPares());
        }
    }

    private void verificarCompletado() {
        if (!ejecutando.get()) return;
        if (gestorPiezas.estaCompleto() && anuncioCompletadoEnviado.compareAndSet(false, true)) {
            System.out.println("[SesionTorrent] ✓ Descarga completa. Enviando announce 'completado'.");
            try {
                if (escritorBloques != null) escritorBloques.sincronizar();
            } catch (IOException e) {
                System.err.println("[SesionTorrent] Error sincronizando archivo: " + e);
            }
            clienteTracker.anunciar(
                    meta.getInfoHash(), miPeerId, puertoEscucha,
                    0, meta.getLongitudTotal(), 0, "completado");
        }
    }

    public void detener() {
        if (!ejecutando.compareAndSet(true, false)) return;

        clienteTracker.desconectar(meta.getInfoHash(), miPeerId, puertoEscucha);

        if (servidorPar != null) servidorPar.detener();
        if (gestorChoke != null) gestorChoke.detener();
        scheduler.shutdownNow();
        poolSesionesP2P.shutdownNow();

        try { if (escritorBloques != null) escritorBloques.close(); }
        catch (IOException ignorada) {}
        try { if (lectorBloques != null) lectorBloques.close(); }
        catch (IOException ignorada) {}

        System.out.println("[SesionTorrent] Sesión detenida.");
    }

    public boolean estaCompleto() { return gestorPiezas != null && gestorPiezas.estaCompleto(); }
    public GestorPiezas getGestorPiezas() { return gestorPiezas; }
}