package utorrent.cluster;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Log causal de eventos (req. 2.2 y 3.4).
 *
 * Cada nodo escribe dos archivos en la carpeta logs/:
 *  - eventos-nodoN.log : legible, con hora de pared + sello de Lamport.
 *  - eventos-nodoN.csv : para la tabla/gráficos del informe. Separador ';'
 *    (lo que Excel en español interpreta por defecto) y columnas:
 *        ts_lamport ; ts_epoch_ms ; nodo ; tipo ; detalle
 *
 * Al fusionar los CSV de los tres nodos y ordenarlos por ts_lamport se obtiene
 * el orden total/causal de la corrida: esa es la evidencia de ordenamiento que
 * pide la rúbrica.
 *
 * La escritura está protegida por un lock y hace flush por línea: si un nodo se
 * mata con kill -9 durante la falla inducida (req. 3.3), el log conserva todo
 * lo ocurrido hasta el instante de la caída.
 */
public final class RegistroEventos implements AutoCloseable {

    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final int idNodo;
    private final RelojLamport reloj;
    private final BufferedWriter log;
    private final BufferedWriter csv;
    private final ReentrantLock lock = new ReentrantLock();

    public RegistroEventos(int idNodo, RelojLamport reloj) throws IOException {
        this.idNodo = idNodo;
        this.reloj = reloj;

        Path dir = Paths.get("logs");
        Files.createDirectories(dir);
        Path rutaLog = dir.resolve("eventos-nodo" + idNodo + ".log");
        Path rutaCsv = dir.resolve("eventos-nodo" + idNodo + ".csv");

        boolean csvNuevo = !Files.exists(rutaCsv);
        this.log = Files.newBufferedWriter(rutaLog, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.csv = Files.newBufferedWriter(rutaCsv, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (csvNuevo) {
            csv.write("ts_lamport;ts_epoch_ms;nodo;tipo;detalle");
            csv.newLine();
            csv.flush();
        }
    }

    /**
     * Registra un evento con un sello de Lamport YA obtenido en el sitio del
     * envío/recepción (con tick() o actualizar()). Se pasa el sello en vez de
     * avanzar el reloj aquí para no contar el mismo evento dos veces.
     */
    public void registrar(long ts, TipoEvento tipo, String detalle) {
        long epoch = System.currentTimeMillis();
        String det = sanear(detalle);
        lock.lock();
        try {
            log.write(String.format("%s [L=%d] [nodo %d] %-20s %s",
                    LocalDateTime.now().format(HORA), ts, idNodo, tipo, det));
            log.newLine();
            log.flush();

            csv.write(ts + ";" + epoch + ";" + idNodo + ";" + tipo + ";" + det);
            csv.newLine();
            csv.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("No pude escribir el log de eventos", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Conveniencia para un evento puramente interno (sin mensaje asociado):
     * avanza el reloj con tick() y registra con ese sello.
     */
    public long registrarLocal(TipoEvento tipo, String detalle) {
        long ts = reloj.tick();
        registrar(ts, tipo, detalle);
        return ts;
    }

    private static String sanear(String s) {
        if (s == null) return "";
        return s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public void close() {
        lock.lock();
        try {
            try { log.flush(); log.close(); } catch (IOException ignorada) {}
            try { csv.flush(); csv.close(); } catch (IOException ignorada) {}
        } finally {
            lock.unlock();
        }
    }
}
