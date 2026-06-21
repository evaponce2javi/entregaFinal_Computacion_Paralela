package utorrent.cluster;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Carga la configuración del clúster de trackers desde "cluster.properties".
 *
 * Formato esperado:
 *   nodo.id=1
 *   nodos=1@127.0.0.1:6969,2@127.0.0.1:6970,3@127.0.0.1:6971
 *
 * "nodos" enumera la membresía COMPLETA (incluido este nodo). El puerto local
 * se deriva de la entrada cuyo id coincide con nodo.id: así la lista de
 * miembros es la única fuente de verdad y evitamos puertos contradictorios
 * entre consola y archivo.
 *
 * Si el archivo no existe o está mal formado, disponible()==false y el
 * ServidorTracker arranca en modo de nodo único (compatibilidad con el
 * Parcial). El archivo se deja fuera del repositorio, igual que
 * tracker.properties, para que cada integrante configure su entorno.
 */
public final class ConfiguracionCluster {

    private static final String ARCHIVO_DEFECTO = "cluster.properties";

    private final boolean disponible;
    private final int idLocal;
    private final InfoNodo local;
    private final List<InfoNodo> nodos;

    private ConfiguracionCluster(boolean disponible, int idLocal,
                                 InfoNodo local, List<InfoNodo> nodos) {
        this.disponible = disponible;
        this.idLocal = idLocal;
        this.local = local;
        this.nodos = nodos;
    }

    public static ConfiguracionCluster cargar() {
        return cargar(Paths.get(ARCHIVO_DEFECTO));
    }

    public static ConfiguracionCluster cargar(Path ruta) {
        if (!Files.exists(ruta)) {
            return noDisponible();
        }
        try (InputStream in = Files.newInputStream(ruta)) {
            Properties p = new Properties();
            p.load(in);

            int idLocal = Integer.parseInt(p.getProperty("nodo.id", "").trim());
            List<InfoNodo> nodos = parsearNodos(p.getProperty("nodos", ""));

            InfoNodo local = null;
            for (InfoNodo n : nodos) {
                if (n.getId() == idLocal) { local = n; break; }
            }
            if (local == null) {
                throw new IllegalArgumentException(
                        "nodo.id=" + idLocal + " no aparece en la lista 'nodos'");
            }
            return new ConfiguracionCluster(true, idLocal, local, nodos);

        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[Cluster] No pude leer " + ruta + ": " + e.getMessage()
                    + ". Arrancando en modo de nodo único.");
            return noDisponible();
        }
    }

    private static ConfiguracionCluster noDisponible() {
        return new ConfiguracionCluster(false, -1, null, new ArrayList<>());
    }

    private static List<InfoNodo> parsearNodos(String csv) {
        List<InfoNodo> lista = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) return lista;
        for (String entrada : csv.split(",")) {
            String e = entrada.trim();
            if (e.isEmpty()) continue;
            // formato: id@host:puerto
            int arroba = e.indexOf('@');
            int dosPuntos = e.lastIndexOf(':');
            if (arroba < 0 || dosPuntos < arroba) {
                throw new IllegalArgumentException("Entrada de nodo inválida: '" + e
                        + "' (esperado id@host:puerto)");
            }
            int id      = Integer.parseInt(e.substring(0, arroba).trim());
            String host = e.substring(arroba + 1, dosPuntos).trim();
            int puerto  = Integer.parseInt(e.substring(dosPuntos + 1).trim());
            lista.add(new InfoNodo(id, host, puerto));
        }
        return lista;
    }

    public boolean disponible()   { return disponible; }
    public int idLocal()          { return idLocal; }
    public int puertoLocal()      { return local.getPuerto(); }
    public InfoNodo local()       { return local; }
    public List<InfoNodo> nodos() { return nodos; }

    public Membresia construirMembresia() {
        if (!disponible) {
            throw new IllegalStateException("No hay configuración de clúster disponible");
        }
        return new Membresia(local, nodos);
    }
}
