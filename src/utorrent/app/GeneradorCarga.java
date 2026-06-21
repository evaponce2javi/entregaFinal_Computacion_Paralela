package utorrent.app;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import utorrent.modelos.RespuestaAnuncio;
import utorrent.tracker.ClienteTracker;

/**
 * Generador de carga (req. 3.1-3.2-3.3-3.4).
 *
 * Lanza N clientes concurrentes (>=50) durante D segundos (>=60) que ejercitan
 * la función de directorio F1: announces (escritura del directorio, protegida
 * por el mutex distribuido de Ricart-Agrawala) y consultas (lectura), repartidos
 * sobre el clúster de trackers con failover. Cada announce dispara, del lado del
 * tracker, una entrada a la sección crítica distribuida: así la carga ejercita
 * el recurso protegido por exclusión mutua, como pide la rúbrica.
 *
 * Métricas recolectadas:
 *   - throughput (peticiones atendidas por segundo),
 *   - latencia promedio y percentil 95 (p95),
 *   - tasa de error,
 *   - y, si se indica el instante de la falla inducida (-Dcarga.fallaEn=segundos),
 *     desglose ANTES vs DESPUÉS de la caída, para leer su impacto (req. 3.3).
 *
 * Volca logs/carga.csv (ts_ms;latencia_ms;exito) por petición, para graficar la
 * serie temporal y correlacionarla con la falla en el informe (req. 3.4). Los
 * mensajes de coordinación (mutex/elección/réplica/latidos) los registran los
 * propios trackers en sus eventos-nodoN.csv (líneas METRICAS).
 *
 * Parámetros (system properties / entorno):
 *   -Dcarga.hilos=50  -Dcarga.duracion=60  -Dcarga.fallaEn=-1
 *   TRACKER_HOSTS="h:p,h:p,h:p"   (por defecto 127.0.0.1:7001-7003)
 */
public final class GeneradorCarga {

    public static void main(String[] args) throws Exception {
        int hilos        = Integer.getInteger("carga.hilos", 50);
        int duracionSeg  = Integer.getInteger("carga.duracion", 60);
        int fallaEnSeg   = Integer.getInteger("carga.fallaEn", -1);
        List<InetSocketAddress> endpoints = endpoints();
        byte[] infoHash  = hashFijo();

        System.out.printf("=== Generador de carga: %d hilos x %d s sobre %s ===%n",
                hilos, duracionSeg, endpoints);
        if (fallaEnSeg > 0) {
            System.out.printf("    (se analizará el corte en t=%d s para la falla inducida)%n", fallaEnSeg);
        }

        final long inicio = System.currentTimeMillis();
        final long deadline = inicio + duracionSeg * 1000L;

        // Registros por hilo (sin contención) -> se fusionan al final.
        List<List<long[]>> porHilo = new ArrayList<>();
        for (int i = 0; i < hilos; i++) porHilo.add(new ArrayList<>());

        LongAdder exitos = new LongAdder(), errores = new LongAdder();
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch fin = new CountDownLatch(hilos);

        for (int h = 0; h < hilos; h++) {
            final int idHilo = h;
            final List<long[]> reg = porHilo.get(h);
            pool.submit(() -> {
                ClienteTracker ct = new ClienteTracker(endpoints);
                String peerId = "carga-" + idHilo;
                int puerto = 20000 + idHilo;
                long op = 0;
                while (System.currentTimeMillis() < deadline) {
                    boolean esConsulta = (op % 5 == 0);   // 20% lecturas, 80% escrituras
                    long t0 = System.nanoTime();
                    boolean ok;
                    try {
                        RespuestaAnuncio r;
                        if (esConsulta) {
                            r = ct.consultarPorNombre("carga-archivo", peerId);
                            // la consulta por nombre puede no existir: cuenta como
                            // atendida si el tracker respondió (exito booleano del protocolo)
                            ok = (r != null);
                        } else {
                            String ev = (op == 0) ? "iniciado" : "actualizado";
                            r = ct.anunciar(infoHash, peerId, puerto, 0, 0, 100, ev);
                            ok = (r != null && r.isExito());
                        }
                    } catch (RuntimeException e) {
                        ok = false;
                    }
                    long latMs = (System.nanoTime() - t0) / 1_000_000L;
                    reg.add(new long[]{ System.currentTimeMillis(), latMs, ok ? 1 : 0 });
                    if (ok) exitos.increment(); else errores.increment();
                    op++;
                }
                fin.countDown();
            });
        }

        fin.await(duracionSeg + 60L, TimeUnit.SECONDS);
        pool.shutdownNow();
        long finReal = System.currentTimeMillis();

        List<long[]> todos = new ArrayList<>();
        for (List<long[]> r : porHilo) todos.addAll(r);

        volcarCsv(todos);
        reportar(todos, inicio, finReal, fallaEnSeg);
    }

    // ---------- análisis ----------

    private static void reportar(List<long[]> regs, long inicio, long fin, int fallaEnSeg) {
        double segReales = (fin - inicio) / 1000.0;
        System.out.println();
        System.out.println("================ RESULTADOS DE CARGA ================");
        resumenVentana("TOTAL", regs, segReales);

        if (fallaEnSeg > 0) {
            long corte = inicio + fallaEnSeg * 1000L;
            List<long[]> antes = new ArrayList<>(), despues = new ArrayList<>();
            for (long[] r : regs) (r[0] < corte ? antes : despues).add(r);
            double segAntes = fallaEnSeg;
            double segDespues = Math.max(0.001, (fin - corte) / 1000.0);
            System.out.println("----------------------------------------------------");
            resumenVentana("ANTES de la falla", antes, segAntes);
            resumenVentana("DESPUÉS de la falla", despues, segDespues);
        }
        System.out.println("=====================================================");
        System.out.println("CSV por-petición: logs/carga.csv");
    }

    private static void resumenVentana(String nombre, List<long[]> regs, double seg) {
        long n = regs.size();
        long ok = 0;
        List<Long> lat = new ArrayList<>((int) n);
        for (long[] r : regs) { if (r[2] == 1) ok++; lat.add(r[1]); }
        long err = n - ok;
        double thr = seg > 0 ? n / seg : 0;
        double prom = 0; for (long l : lat) prom += l; prom = n > 0 ? prom / n : 0;
        long p95 = percentil(lat, 95);
        double tasaErr = n > 0 ? (100.0 * err / n) : 0;
        System.out.printf("[%s] peticiones=%d  throughput=%.1f req/s  latencia_prom=%.1f ms  "
                        + "p95=%d ms  errores=%d (%.2f%%)%n",
                nombre, n, thr, prom, p95, err, tasaErr);
    }

    private static long percentil(List<Long> valores, int p) {
        if (valores.isEmpty()) return 0;
        List<Long> copia = new ArrayList<>(valores);
        Collections.sort(copia);
        int idx = (int) Math.ceil(p / 100.0 * copia.size()) - 1;
        idx = Math.max(0, Math.min(idx, copia.size() - 1));
        return copia.get(idx);
    }

    private static void volcarCsv(List<long[]> regs) {
        try {
            Path dir = Paths.get("logs");
            Files.createDirectories(dir);
            Path csv = dir.resolve("carga.csv");
            try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                w.write("ts_ms;latencia_ms;exito");
                w.newLine();
                for (long[] r : regs) {
                    w.write(r[0] + ";" + r[1] + ";" + r[2]);
                    w.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("[Carga] no pude escribir logs/carga.csv: " + e.getMessage());
        }
    }

    // ---------- configuración ----------

    private static List<InetSocketAddress> endpoints() {
        String env = System.getenv("TRACKER_HOSTS");
        List<InetSocketAddress> eps = new ArrayList<>();
        if (env != null && !env.isBlank()) {
            for (String s : env.split(",")) {
                String e = s.trim();
                int i = e.lastIndexOf(':');
                eps.add(new InetSocketAddress(e.substring(0, i), Integer.parseInt(e.substring(i + 1))));
            }
        } else {
            eps.add(new InetSocketAddress("127.0.0.1", 7001));
            eps.add(new InetSocketAddress("127.0.0.1", 7002));
            eps.add(new InetSocketAddress("127.0.0.1", 7003));
        }
        return eps;
    }

    private static byte[] hashFijo() {
        byte[] h = new byte[20];
        for (int i = 0; i < 20; i++) h[i] = (byte) (i + 1);
        return h;
    }
}
