package utorrent.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

/**
 * Centraliza las preguntas de configuración al usuario por consola.
 */
public class ConfiguracionRed {

    private static final String ENV_TRACKER_HOST = "TRACKER_HOST";
    private static final String ENV_TRACKER_PORT = "TRACKER_PORT";
    private static final String ARCHIVO_CONFIG   = "tracker.properties";

    private final Scanner sc;
    private final Properties props;

    public ConfiguracionRed(Scanner sc) {
        this.sc    = sc;
        this.props = cargarPropiedades();
    }

    public String pedirIpTracker() {
        String host = System.getenv(ENV_TRACKER_HOST);
        if (estaDefinido(host)) {
            System.out.println("[Config] Tracker host desde variable de entorno ("
                    + ENV_TRACKER_HOST + "): " + host.trim());
            return host.trim();
        }

        String propHost = props.getProperty("tracker.host");
        if (estaDefinido(propHost)) {
            System.out.println("[Config] Tracker host desde " + ARCHIVO_CONFIG
                    + ": " + propHost.trim());
            return propHost.trim();
        }

        System.out.print("IP del Tracker: ");
        return sc.nextLine().trim();
    }

    /**
     * Devuelve el puerto del tracker, resolviéndolo en el mismo orden de prioridad.
     */
    public int pedirPuertoTracker() {
        String portEnv = System.getenv(ENV_TRACKER_PORT);
        if (estaDefinido(portEnv)) {
            try {
                int p = Integer.parseInt(portEnv.trim());
                System.out.println("[Config] Tracker puerto desde variable de entorno ("
                        + ENV_TRACKER_PORT + "): " + p);
                return p;
            } catch (NumberFormatException e) {
                System.err.println("[Config] " + ENV_TRACKER_PORT
                        + " no es un número válido: " + portEnv);
            }
        }

        String propPort = props.getProperty("tracker.port");
        if (estaDefinido(propPort)) {
            try {
                int p = Integer.parseInt(propPort.trim());
                System.out.println("[Config] Tracker puerto desde " + ARCHIVO_CONFIG + ": " + p);
                return p;
            } catch (NumberFormatException e) {
                System.err.println("[Config] tracker.port en " + ARCHIVO_CONFIG
                        + " no es un número válido: " + propPort);
            }
        }

        System.out.print("Puerto del Tracker: ");
        return Integer.parseInt(sc.nextLine().trim());
    }

    /**
     * Devuelve la LISTA de endpoints del clúster de trackers para el failover
     * (Paso 7). Prioridad:
     *   1) variable de entorno TRACKER_HOSTS = "host:puerto,host:puerto,..."
     *   2) propiedad tracker.hosts en tracker.properties (mismo formato)
     *   3) un solo endpoint, resuelto por pedirIpTracker()/pedirPuertoTracker()
     */
    public List<InetSocketAddress> pedirEndpointsTracker() {
        String env = System.getenv("TRACKER_HOSTS");
        if (estaDefinido(env)) {
            System.out.println("[Config] Trackers desde TRACKER_HOSTS: " + env.trim());
            return parsearEndpoints(env);
        }
        String prop = props.getProperty("tracker.hosts");
        if (estaDefinido(prop)) {
            System.out.println("[Config] Trackers desde " + ARCHIVO_CONFIG
                    + " (tracker.hosts): " + prop.trim());
            return parsearEndpoints(prop);
        }
        List<InetSocketAddress> uno = new ArrayList<>();
        uno.add(new InetSocketAddress(pedirIpTracker(), pedirPuertoTracker()));
        return uno;
    }

    private static List<InetSocketAddress> parsearEndpoints(String csv) {
        List<InetSocketAddress> lista = new ArrayList<>();
        for (String entrada : csv.split(",")) {
            String s = entrada.trim();
            if (s.isEmpty()) continue;
            int i = s.lastIndexOf(':');
            if (i < 0) {
                throw new IllegalArgumentException("Endpoint inválido: '" + s
                        + "' (esperado host:puerto)");
            }
            String host = s.substring(0, i).trim();
            int puerto = Integer.parseInt(s.substring(i + 1).trim());
            lista.add(new InetSocketAddress(host, puerto));
        }
        if (lista.isEmpty()) {
            throw new IllegalArgumentException("La lista de trackers está vacía");
        }
        return lista;
    }

    public int pedirPuertoEscuchaLocal() {
        System.out.print("Puerto local de escucha P2P: ");
        return Integer.parseInt(sc.nextLine().trim());
    }

    public String pedirRutaArchivo() {
        System.out.print("Ruta del archivo a compartir: ");
        return sc.nextLine().trim();
    }

    public String pedirNombreArchivo() {
        System.out.print("Nombre del archivo a descargar: ");
        return sc.nextLine().trim();
    }

    public String pedirCarpetaDestino() {
        System.out.print("Carpeta de destino: ");
        return sc.nextLine().trim();
    }

    private Properties cargarPropiedades() {
        Properties p = new Properties();
        Path ruta = Paths.get(ARCHIVO_CONFIG);
        if (Files.exists(ruta)) {
            try (InputStream is = new FileInputStream(ruta.toFile())) {
                p.load(is);
                System.out.println("[Config] Configuración cargada desde " + ARCHIVO_CONFIG);
            } catch (IOException e) {
                System.err.println("[Config] No se pudo leer " + ARCHIVO_CONFIG
                        + ": " + e.getMessage());
            }
        }
        return p;
    }

    private static boolean estaDefinido(String valor) {
        return valor != null && !valor.isBlank();
    }
}