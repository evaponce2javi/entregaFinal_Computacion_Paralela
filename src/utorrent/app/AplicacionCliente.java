package utorrent.app;

import utorrent.modelos.MetadatosTorrent;
import utorrent.modelos.RespuestaAnuncio;
import utorrent.tracker.ClienteTracker;
import utorrent.utils.CalculadorHash;
import utorrent.utils.ConfiguracionRed;
import utorrent.utils.EscritorBloques;
import utorrent.utils.GeneradorPeerId;
import utorrent.utils.LectorBloques;
import utorrent.utils.LimitesArchivo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;

/**
 * Punto de entrada del cliente. Maneja las dos funciones del sistema:
 * 
 * 1. Compartir (Seeder): Hashea el archivo, avisa al tracker y se queda esperando 
 *    conexiones para servir bloques.
 * 2. Descargar (Leecher): Busca el archivo, contacta a otros usuarios y baja los 
 *    bloques de 16 KB. Al terminar, se pone a compartir automáticamente.
 * 
 * La idea es que no haya diferencia entre quien sube y quien baja: una vez que 
 * tienes el archivo (o parte de él), lo tratas igual y lo compartes. 
 * Además, el cliente busca solo la dirección del tracker usando la configuración 
 * del sistema para que el usuario no tenga que configurar IPs a mano.
 */

public class AplicacionCliente {

    public static final int LONGITUD_PIEZA_DEFECTO = 256 * 1024;

    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("   uTorrent académico — Cliente P2P");
        System.out.println("=========================================");

        try (Scanner sc = new Scanner(System.in)) {
            ConfiguracionRed config = new ConfiguracionRed(sc);

            java.util.List<java.net.InetSocketAddress> trackers = config.pedirEndpointsTracker();
            int    puertoEscucha = config.pedirPuertoEscuchaLocal();
            String miPeerId      = GeneradorPeerId.generar();
            System.out.println("Mi peerId: " + miPeerId);

            ClienteTracker clienteTracker = new ClienteTracker(trackers);

            System.out.println();
            System.out.println("Selecciona una opción:");
            System.out.println("  1. Compartir archivo (Seeder)");
            System.out.println("  2. Descargar archivo (Leecher)");
            System.out.print("Opción: ");
            String opcion = sc.nextLine().trim();

            switch (opcion) {
                case "1":
                    ejecutarSeeder(sc, config, clienteTracker, miPeerId, puertoEscucha);
                    break;
                case "2":
                    ejecutarLeecher(sc, config, clienteTracker, miPeerId, puertoEscucha);
                    break;
                default:
                    System.err.println("Opción no válida.");
            }
        } catch (Exception e) {
            System.err.println("Error fatal: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Seeder
    private static void ejecutarSeeder(Scanner sc, ConfiguracionRed config,
                                       ClienteTracker clienteTracker,
                                       String miPeerId, int puertoEscucha) throws IOException {
        Path archivo = Paths.get(config.pedirRutaArchivo());

        try {
            LimitesArchivo.validarParaSeeding(archivo);
        } catch (IllegalArgumentException e) {
            System.err.println("[Seeder] " + e.getMessage());
            return;
        }

        long longitudTotal = Files.size(archivo);
        int  longitudPieza = LONGITUD_PIEZA_DEFECTO;
        System.out.printf("[Seeder] Hasheando %s (%d bytes en piezas de %d bytes)...%n",
                archivo.getFileName(), longitudTotal, longitudPieza);

        List<byte[]> hashesPiezas = CalculadorHash.hashearArchivoPorPiezas(archivo, longitudPieza);
        byte[] infoHash = CalculadorHash.calcularInfoHash(
                archivo.getFileName().toString(), longitudTotal, longitudPieza, hashesPiezas);

        MetadatosTorrent meta = new MetadatosTorrent(
                archivo.getFileName().toString(),
                longitudTotal, longitudPieza, infoHash, hashesPiezas,
                clienteTracker.toString(), 0);

        System.out.println("[Seeder] infoHash = " + CalculadorHash.aHex(infoHash));
        System.out.println("[Seeder] piezas   = " + hashesPiezas.size());

        RespuestaAnuncio resp = clienteTracker.publicarSeed(meta, miPeerId, puertoEscucha);
        if (resp == null || !resp.isExito()) {
            System.err.println("[Seeder] No pude publicar al tracker. Abortando.");
            return;
        }

        LectorBloques lector = new LectorBloques(archivo);
        iniciarYEsperarSeeder(sc, clienteTracker, miPeerId, puertoEscucha, meta, lector);
    }

    //Leecher
    private static void ejecutarLeecher(Scanner sc, ConfiguracionRed config,
                                        ClienteTracker clienteTracker,
                                        String miPeerId, int puertoEscucha) throws IOException {
        String nombreArchivo  = config.pedirNombreArchivo();
        String carpetaDestino = config.pedirCarpetaDestino();

        System.out.println("[Leecher] Consultando al tracker por '" + nombreArchivo + "'...");
        RespuestaAnuncio respuestaConsulta = clienteTracker.consultarPorNombre(
                nombreArchivo, miPeerId);
        if (respuestaConsulta == null || !respuestaConsulta.isExito()) {
            System.err.println("[Leecher] El tracker no conoce el archivo solicitado.");
            return;
        }

        MetadatosTorrent meta = respuestaConsulta.getMetadatos();
        if (meta == null) {
            System.err.println("[Leecher] El tracker no entregó los metadatos del archivo.");
            return;
        }
        System.out.printf("[Leecher] Archivo encontrado: %d bytes en %d piezas de %d bytes%n",
                meta.getLongitudTotal(), meta.totalPiezas(), meta.getLongitudPieza());

        Path destino = Paths.get(carpetaDestino, meta.getNombreArchivo());
        EscritorBloques escritor = new EscritorBloques(destino, meta.getLongitudTotal());
        System.out.println("[Leecher] Espacio reservado en " + destino);

        // ── Fase de descarga ────────────────────────────────────────────
        SesionTorrent sesionLeecher = new SesionTorrent(
                meta, miPeerId, puertoEscucha,
                SesionTorrent.Modo.LEECHER,
                clienteTracker, null, escritor);

        Thread hookDescarga = new Thread(sesionLeecher::detener,
                "shutdown-leecher");
        Runtime.getRuntime().addShutdownHook(hookDescarga);

        sesionLeecher.iniciar();
        descargaConProgreso(sesionLeecher);

        if (!sesionLeecher.estaCompleto()) {
            // Timeout/interrupción
            sesionLeecher.detener();
            quitarHook(hookDescarga);
            return;
        }

        System.out.println("[Leecher] ✓ Descarga completa: " + destino);

        sesionLeecher.detener();
        quitarHook(hookDescarga);

        System.out.println();
        System.out.print("¿Deseas compartir el archivo con otros peers? (s/n): ");
        String respuesta = sc.nextLine().trim().toLowerCase();

        if (respuesta.equals("s") || respuesta.equals("si") || respuesta.equals("sí")) {
            System.out.println("[Seeder] ── Activando modo seeder con el archivo descargado ──");
            LectorBloques lector = new LectorBloques(destino);
            iniciarYEsperarSeeder(sc, clienteTracker, miPeerId, puertoEscucha, meta, lector);
        } else {
            System.out.println("[Cliente] Sesión finalizada. El archivo está en: " + destino);
        }
    }

    // Helpers
    private static void iniciarYEsperarSeeder(Scanner sc,
                                               ClienteTracker clienteTracker,
                                               String miPeerId, int puertoEscucha,
                                               MetadatosTorrent meta,
                                               LectorBloques lector) throws IOException {
        SesionTorrent sesion = new SesionTorrent(
                meta, miPeerId, puertoEscucha,
                SesionTorrent.Modo.SEEDER,
                clienteTracker, lector, null);

        Thread hookSeeder = new Thread(sesion::detener, "shutdown-seeder");
        Runtime.getRuntime().addShutdownHook(hookSeeder);

        sesion.iniciar();
        System.out.println("[Seeder] Compartiendo. Presiona ENTER para detener...");

        sc.nextLine();

        sesion.detener();
        quitarHook(hookSeeder);

        try { lector.close(); } catch (IOException ignorada) {}
    }

    /**
     * Bucle de progreso de la descarga. Imprime el porcentaje en la misma
     * línea y aplica un timeout de 10 minutos.
     */
    private static void descargaConProgreso(SesionTorrent sesion) {
        System.out.println("[Leecher] Descarga en curso...");
        long inicio = System.currentTimeMillis();

        while (!sesion.estaCompleto()) {
            try { Thread.sleep(500); } catch (InterruptedException ie) { break; }

            if (sesion.getGestorPiezas() != null) {
                int hechas = sesion.getGestorPiezas().piezasCompletadas();
                int total  = sesion.getGestorPiezas().totalPiezas();
                System.out.printf("\r[Leecher] Progreso: %d/%d piezas (%.1f%%)",
                        hechas, total, hechas * 100.0 / total);
            }

            if (System.currentTimeMillis() - inicio > 10 * 60 * 1_000L) {
                System.err.println("\n[Leecher] Timeout: 10 minutos sin completar.");
                break;
            }
        }
        System.out.println();
    }

    /**
     * Elimina un shutdown hook registrado previamente. Necesario al hacer
     * la transición leecher -> seeder para que la JVM no intente ejecutar
     * el hook de la sesión ya detenida.
     */
    private static void quitarHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignorada) {
        }
    }
}