package utorrent.cluster;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Detector de fallos por heartbeats + timeouts (Unidad 6, req. 2.4).
 *
 * Cada nodo envía un latido periódico (cada T) a todos sus pares y vigila los
 * latidos que recibe. Transiciones de estado en Membresia:
 *   - recepción de latido            -> el par pasa a VIVO (promoción/reintegración)
 *   - sin latido por > umbralSospecha -> SOSPECHOSO
 *   - sin latido por > umbralCaida     -> CAIDO  (dispara onCambioEstado: R-A
 *     recalcula su quórum y, en el Paso 6, Bully reacciona si cayó el coordinador)
 *
 * Distinción crash vs omisión (req. 2.4):
 *   - crash  : cese total de latidos -> el umbral temporal lo marca CAIDO.
 *   - omisión: hueco hacia adelante en la secuencia de un emisor (algunos
 *     latidos no llegaron) -> se registra como FALLO_SOSPECHA sin marcar caída.
 *
 * Robustez ante falsos positivos (GC, saturación bajo la carga del Paso 8):
 *   umbrales holgados (k>=3 latidos) y el estado intermedio SOSPECHOSO antes de
 *   CAIDO, para no disparar elecciones espurias.
 *
 * El detector es la única fuente de promociones a VIVO: hasta el primer latido,
 * los pares permanecen SOSPECHOSO (periodo de gracia del arranque, sembrado con
 * ultimoVisto = ahora).
 */
public final class DetectorFallos {

    private final Membresia membresia;
    private final RelojLamport reloj;
    private final RegistroEventos eventos;
    private final Mensajero mensajero;

    private final long intervaloMs;
    private final long umbralSospechaMs;
    private final long umbralCaidaMs;

    private final ConcurrentHashMap<Integer, Long> ultimoVisto = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> ultimaSecuencia = new ConcurrentHashMap<>();
    private final AtomicLong miSecuencia = new AtomicLong(0);
    private final LongAdder latidosEnviados = new LongAdder();

    private ScheduledExecutorService planificador;

    public DetectorFallos(Membresia membresia, RelojLamport reloj,
                          RegistroEventos eventos, Mensajero mensajero) {
        this(membresia, reloj, eventos, mensajero, 1000, 2500, 4000);
    }

    public DetectorFallos(Membresia membresia, RelojLamport reloj,
                          RegistroEventos eventos, Mensajero mensajero,
                          long intervaloMs, long umbralSospechaMs, long umbralCaidaMs) {
        this.membresia = membresia;
        this.reloj = reloj;
        this.eventos = eventos;
        this.mensajero = mensajero;
        this.intervaloMs = intervaloMs;
        this.umbralSospechaMs = umbralSospechaMs;
        this.umbralCaidaMs = umbralCaidaMs;
        long ahora = System.currentTimeMillis();
        for (int id : membresia.paresTotales()) ultimoVisto.put(id, ahora); // gracia inicial
    }

    public void iniciar() {
        AtomicInteger n = new AtomicInteger(1);
        planificador = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "detector-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        planificador.scheduleAtFixedRate(this::enviarLatidos,
                intervaloMs, intervaloMs, TimeUnit.MILLISECONDS);
        planificador.scheduleAtFixedRate(this::revisar,
                intervaloMs, intervaloMs, TimeUnit.MILLISECONDS);
        System.out.printf("[Detector] activo (T=%dms, sospecha=%dms, caida=%dms)%n",
                intervaloMs, umbralSospechaMs, umbralCaidaMs);
    }

    private void enviarLatidos() {
        try {
            long seq = miSecuencia.incrementAndGet();
            for (int id : membresia.paresTotales()) {
                mensajero.enviar(id, new MensajeHeartbeat(membresia.idLocal(), seq));
                latidosEnviados.increment();
            }
        } catch (RuntimeException e) {
            System.err.println("[Detector] error enviando latidos: " + e.getMessage());
        }
    }

    private void revisar() {
        try {
            long ahora = System.currentTimeMillis();
            for (int id : membresia.paresTotales()) {
                long transcurrido = ahora - ultimoVisto.getOrDefault(id, 0L);
                EstadoNodo actual = membresia.estado(id);
                if (transcurrido > umbralCaidaMs) {
                    if (actual != EstadoNodo.CAIDO) {
                        eventos.registrar(reloj.tick(), TipoEvento.FALLO_CAIDA,
                                "nodo " + id + " CAIDO (sin latido por " + transcurrido + " ms)");
                        membresia.transicionar(id, EstadoNodo.CAIDO);
                    }
                } else if (transcurrido > umbralSospechaMs) {
                    if (actual == EstadoNodo.VIVO) {
                        eventos.registrar(reloj.tick(), TipoEvento.FALLO_SOSPECHA,
                                "nodo " + id + " SOSPECHOSO (sin latido por " + transcurrido + " ms)");
                        membresia.transicionar(id, EstadoNodo.SOSPECHOSO);
                    }
                }
            }
        } catch (RuntimeException e) {
            System.err.println("[Detector] error revisando: " + e.getMessage());
        }
    }

    /** Procesa un latido recibido: actualiza liveness, detecta omisión y promueve. */
    public void recibir(MensajeHeartbeat hb) {
        int id = hb.getNodoOrigen();
        ultimoVisto.put(id, System.currentTimeMillis());

        Long prev = ultimaSecuencia.get(id);
        if (prev != null && hb.getSecuencia() > prev + 1) {
            long hueco = hb.getSecuencia() - prev - 1;
            eventos.registrar(reloj.valor(), TipoEvento.FALLO_SOSPECHA,
                    "omisión: hueco de " + hueco + " latido(s) del nodo " + id);
        }
        ultimaSecuencia.put(id, hb.getSecuencia());

        EstadoNodo actual = membresia.estado(id);
        if (actual != EstadoNodo.VIVO) {
            boolean reintegrado = (actual == EstadoNodo.CAIDO);
            eventos.registrar(reloj.tick(), TipoEvento.HEARTBEAT,
                    "nodo " + id + " VIVO" + (reintegrado ? " (reintegrado)" : ""));
            membresia.transicionar(id, EstadoNodo.VIVO);
        }
    }

    public long latidosEnviados() { return latidosEnviados.sum(); }

    public void detener() {
        if (planificador != null) planificador.shutdownNow();
    }
}
