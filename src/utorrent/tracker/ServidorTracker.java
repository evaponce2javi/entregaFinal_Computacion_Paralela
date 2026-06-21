package utorrent.tracker;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import utorrent.cluster.CanalReplica;
import utorrent.cluster.ConfiguracionCluster;
import utorrent.cluster.ContextoCluster;
import utorrent.cluster.DetectorFallos;
import utorrent.cluster.EleccionBully;
import utorrent.cluster.ExclusionRA;
import utorrent.cluster.InfoNodo;
import utorrent.cluster.Membresia;
import utorrent.cluster.Mensajero;
import utorrent.cluster.MensajeroSocket;
import utorrent.cluster.RegistroEventos;
import utorrent.cluster.RelojLamport;
import utorrent.cluster.TipoEvento;

/**
 * Servidor centralizado que coordina el descubrimiento de peers.
 */
public class ServidorTracker {

    private static final int TAMANO_POOL_DEFECTO = 32;

    private final int puerto;
    private final ExecutorService poolHilos;
    private final ScheduledExecutorService mantenimiento;
    private final RegistroPares registro;
    private final Membresia membresia;
    private final RelojLamport reloj;
    private RegistroEventos eventos;
    private CanalReplica canalReplica;
    private ExclusionRA exclusionRA;
    private DetectorFallos detectorFallos;
    private EleccionBully eleccionBully;
    private ContextoCluster contexto;
    private volatile boolean ejecutando = false;
    private ServerSocket socketServidor;

    /** Constructor de nodo único (compatibilidad con el Parcial). */
    public ServidorTracker(int puerto, int maxParesPorIp, int tamanoPool) {
        this(puerto, maxParesPorIp, tamanoPool,
             new Membresia(new InfoNodo(0, "127.0.0.1", puerto), Collections.emptyList()));
    }

    /** Constructor de nodo de clúster (arquitectura multiservidor, req. 2.1). */
    public ServidorTracker(int puerto, int maxParesPorIp, int tamanoPool,
                           Membresia membresia) {
        this.puerto = puerto;
        this.membresia = membresia;
        this.reloj = new RelojLamport();
        this.registro = new RegistroPares(maxParesPorIp);
        this.poolHilos = Executors.newFixedThreadPool(tamanoPool, r -> {
            Thread t = new Thread(r, "tracker-handler");
            t.setDaemon(false);
            return t;
        });
        this.mantenimiento = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tracker-mantenimiento");
            t.setDaemon(true);
            return t;
        });
    }

    public Membresia getMembresia() { return membresia; }
    public RelojLamport getReloj() { return reloj; }
    public RegistroEventos getEventos() { return eventos; }

    public void iniciar() throws IOException {
        ejecutando = true;
        socketServidor = new ServerSocket(puerto);
        System.out.printf("[Tracker] Escuchando en puerto %d (maxParesPorIp=%d, pool=%d hilos)%n",
                puerto, RegistroPares.MAX_PARES_POR_IP_DEFECTO,
                ((java.util.concurrent.ThreadPoolExecutor) poolHilos).getMaximumPoolSize());
        System.out.println("[Cluster] " + membresia);

        eventos = new RegistroEventos(membresia.idLocal(), reloj);
        eventos.registrarLocal(TipoEvento.LOCAL, "tracker iniciado en puerto " + puerto);

        canalReplica = new CanalReplica(membresia);
        Mensajero mensajero = new MensajeroSocket(membresia);
        exclusionRA = new ExclusionRA(membresia, reloj, eventos, mensajero);
        long hbInt   = Long.getLong("detector.intervalo", 1000L);
        long hbSosp  = Long.getLong("detector.sospecha", 2500L);
        long hbCaida = Long.getLong("detector.caida", 4000L);
        detectorFallos = new DetectorFallos(membresia, reloj, eventos, mensajero,
                hbInt, hbSosp, hbCaida);
        long bullyOk    = Long.getLong("bully.timeoutOk", 1500L);
        long bullyCoord = Long.getLong("bully.timeoutCoord", 3000L);
        eleccionBully = new EleccionBully(membresia, reloj, eventos, mensajero,
                bullyOk, bullyCoord);
        contexto = new ContextoCluster(membresia, reloj, eventos, canalReplica,
                exclusionRA, detectorFallos, eleccionBully);
        if (membresia.tamano() > 1) {
            detectorFallos.iniciar();
            mantenimiento.scheduleAtFixedRate(this::registrarMetricasCluster,
                    10, 10, TimeUnit.SECONDS);
        }

        mantenimiento.scheduleAtFixedRate(registro::reiniciarVentana,
                60, 60, TimeUnit.SECONDS);

        while (ejecutando) {
            try {
                Socket cliente = socketServidor.accept();
                poolHilos.submit(new ManejadorAnuncio(cliente, registro, contexto));
            } catch (IOException e) {
                if (ejecutando) {
                    System.err.println("[Tracker] Error aceptando conexión: " + e.getMessage());
                }
            }
        }
    }

    private void registrarMetricasCluster() {
        if (eventos == null) return;
        eventos.registrar(reloj.valor(), TipoEvento.METRICAS, String.format(
                "coordinador=%d mutexMsgs=%d replicaMsgs=%d eleccionMsgs=%d latidos=%d",
                eleccionBully.getCoordinador(),
                exclusionRA.mensajesCoordinacion(),
                canalReplica.mensajesEnviados(),
                eleccionBully.mensajesCoordinacion(),
                detectorFallos.latidosEnviados()));
    }

    public void detener() {
        ejecutando = false;
        try {
            if (socketServidor != null) socketServidor.close();
        } catch (IOException ignorada) {}
        poolHilos.shutdownNow();
        mantenimiento.shutdownNow();
        if (canalReplica != null) canalReplica.detener();
        if (exclusionRA != null) exclusionRA.detener();
        if (detectorFallos != null) detectorFallos.detener();
        if (eleccionBully != null) eleccionBully.detener();
        if (membresia != null && membresia.tamano() > 1) registrarMetricasCluster();
        if (eventos != null) eventos.close();
        System.out.println("[Tracker] Detenido.");
    }

    public static void main(String[] args) {
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("=== Servidor Tracker BitTorrent ===");

            ConfiguracionCluster cfg = ConfiguracionCluster.cargar();

            int puerto;
            Membresia membresia;

            if (cfg.disponible()) {
                // El puerto y la membresía vienen del archivo: única fuente de verdad.
                puerto = cfg.puertoLocal();
                membresia = cfg.construirMembresia();
                System.out.printf("[Cluster] Nodo de clúster id=%d puerto=%d (desde cluster.properties)%n",
                        cfg.idLocal(), puerto);
            } else {
                System.out.print("Puerto de escucha (sugerido 6969): ");
                puerto = Integer.parseInt(sc.nextLine().trim());
                membresia = new Membresia(new InfoNodo(0, "127.0.0.1", puerto),
                        Collections.emptyList());
                System.out.println("[Cluster] Sin cluster.properties: modo de nodo único.");
            }

            System.out.print("Máximo de peers por IP (sugerido 3): ");
            int maxPorIp = Integer.parseInt(sc.nextLine().trim());

            System.out.print("Tamaño del pool de hilos (sugerido 32): ");
            int pool = Integer.parseInt(sc.nextLine().trim());

            ServidorTracker servidor = new ServidorTracker(puerto, maxPorIp, pool, membresia);
            Runtime.getRuntime().addShutdownHook(new Thread(servidor::detener));
            servidor.iniciar();

        } catch (NumberFormatException e) {
            System.err.println("Error: debe ingresar un número válido.");
        } catch (IOException e) {
            System.err.println("Error iniciando el servidor: " + e.getMessage());
        }
    }
}