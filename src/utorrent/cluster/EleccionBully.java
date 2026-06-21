package utorrent.cluster;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Elección de coordinador — algoritmo Bully (Unidad 6, req. 2.3) y pieza de
 * recuperación de la tolerancia a fallos (req. 2.4).
 *
 * El coordinador es el nodo VIVO de id más alto. Al construirse, cada nodo asume
 * como coordinador al id máximo conocido (acuerdo determinista, sin elección de
 * arranque). La elección se dispara solo cuando:
 *   - el detector marca CAIDO al coordinador actual, o
 *   - reaparece (VIVO) un nodo de id mayor al coordinador actual (debe "abusar"
 *     y tomar el mando).
 *
 * Protocolo:
 *   - iniciarEleccion: envía ELECTION a los nodos alcanzables de id mayor. Si
 *     alguno responde OK dentro de T_OK, se retira y espera COORDINATOR (con su
 *     propio timeout para reintentar si ese superior también cae). Si nadie
 *     responde, se proclama coordinador y difunde COORDINATOR.
 *   - recibir ELECTION (de un id menor): responde OK e inicia su propia elección.
 *   - recibir COORDINATOR: acepta al nuevo coordinador (con guarda anti-stale).
 *
 * Split-brain / elecciones concurrentes: se resuelven por el orden total de ids
 * (gana el mayor) y porque un nodo solo se proclama si NINGÚN superior responde;
 * mientras un superior esté vivo, el inferior se retira. COORDINATOR se acepta
 * solo si es de id >= al coordinador vigente, o si el vigente está CAIDO, lo que
 * descarta anuncios obsoletos.
 *
 * No hace falta "rebootstrap" del quórum de R-A tras elegir: cada nodo mantiene
 * su vista de membresía de forma continua con el detector, y Ricart-Agrawala
 * recalcula su quórum en cada entrada a la sección crítica.
 */
public final class EleccionBully implements OyenteMembresia {

    private final Membresia membresia;
    private final RelojLamport reloj;
    private final RegistroEventos eventos;
    private final Mensajero mensajero;

    private final long timeoutOkMs;
    private final long timeoutCoordMs;

    private final ReentrantLock lock = new ReentrantLock();
    private int coordinadorActual;
    private boolean enEleccion = false;
    private boolean recibioOK = false;
    private ScheduledFuture<?> tareaTimeoutOk;
    private ScheduledFuture<?> tareaTimeoutCoord;

    private final ScheduledExecutorService planificador;
    private final ExecutorService poolEnvio;
    private final LongAdder mensajesCoordinacion = new LongAdder();

    public EleccionBully(Membresia membresia, RelojLamport reloj,
                         RegistroEventos eventos, Mensajero mensajero) {
        this(membresia, reloj, eventos, mensajero, 1500, 3000);
    }

    public EleccionBully(Membresia membresia, RelojLamport reloj,
                         RegistroEventos eventos, Mensajero mensajero,
                         long timeoutOkMs, long timeoutCoordMs) {
        this.membresia = membresia;
        this.reloj = reloj;
        this.eventos = eventos;
        this.mensajero = mensajero;
        this.timeoutOkMs = timeoutOkMs;
        this.timeoutCoordMs = timeoutCoordMs;
        this.coordinadorActual = membresia.idMaximo();
        this.planificador = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "bully-timer"); t.setDaemon(true); return t;
        });
        AtomicInteger n = new AtomicInteger(1);
        this.poolEnvio = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "bully-envio-" + n.getAndIncrement()); t.setDaemon(true); return t;
        });
        membresia.registrarOyente(this);
        eventos.registrar(reloj.valor(), TipoEvento.ELECCION_COORDINADOR,
                "coordinador inicial asumido = " + coordinadorActual);
    }

    public int getCoordinador() {
        lock.lock();
        try { return coordinadorActual; } finally { lock.unlock(); }
    }

    public boolean esCoordinador() { return getCoordinador() == membresia.idLocal(); }

    public void iniciarEleccion() {
        lock.lock();
        try {
            if (enEleccion) return;
            enEleccion = true;
            recibioOK = false;
            cancelarTimers();
        } finally { lock.unlock(); }

        List<Integer> superiores = membresia.alcanzablesConIdMayor();
        eventos.registrar(reloj.tick(), TipoEvento.ELECCION_INICIO,
                "inicio elección, superiores=" + superiores);

        if (superiores.isEmpty()) {
            proclamarme();
            return;
        }
        for (int id : superiores) enviar(MensajeEleccion.election(membresia.idLocal()), id);
        lock.lock();
        try {
            tareaTimeoutOk = planificador.schedule(this::venceTimeoutOk,
                    timeoutOkMs, TimeUnit.MILLISECONDS);
        } finally { lock.unlock(); }
    }

    private void venceTimeoutOk() {
        boolean proclamar = false;
        lock.lock();
        try {
            if (enEleccion && !recibioOK) proclamar = true;
        } finally { lock.unlock(); }
        if (proclamar) proclamarme();
    }

    private void venceTimeoutCoord() {
        boolean reiniciar;
        lock.lock();
        try {
            reiniciar = enEleccion;   // si ya se resolvió (COORDINATOR llegó), no reiniciar
            if (reiniciar) enEleccion = false;
        } finally { lock.unlock(); }
        if (reiniciar) iniciarEleccion();
    }

    private void proclamarme() {
        lock.lock();
        try {
            coordinadorActual = membresia.idLocal();
            enEleccion = false;
            recibioOK = false;
            cancelarTimers();
        } finally { lock.unlock(); }
        eventos.registrar(reloj.tick(), TipoEvento.ELECCION_COORDINADOR,
                "me proclamo COORDINADOR (id " + membresia.idLocal() + ")");
        for (int id : membresia.paresAlcanzables()) {
            enviar(MensajeEleccion.coordinator(membresia.idLocal()), id);
        }
    }

    public void recibir(MensajeEleccion msg) {
        switch (msg.getTipo()) {
            case ELECTION:
                if (esCoordinador()) {
                    // Ya soy el coordinador: informo directamente, sin re-elegir.
                    eventos.registrar(reloj.tick(), TipoEvento.ELECCION_COORDINADOR,
                            "ELECTION de nodo " + msg.getNodoOrigen()
                                    + " -> ya soy coordinador, respondo COORDINATOR");
                    enviar(MensajeEleccion.coordinator(membresia.idLocal()), msg.getNodoOrigen());
                } else {
                    eventos.registrar(reloj.tick(), TipoEvento.ELECCION_INICIO,
                            "ELECTION recibido de nodo " + msg.getNodoOrigen() + " -> respondo OK");
                    enviar(MensajeEleccion.ok(membresia.idLocal()), msg.getNodoOrigen());
                    iniciarEleccion();
                }
                break;

            case OK:
                boolean vigente = false;
                lock.lock();
                try {
                    if (enEleccion) {
                        vigente = true;
                        recibioOK = true;
                        if (tareaTimeoutOk != null) tareaTimeoutOk.cancel(false);
                        if (tareaTimeoutCoord != null) tareaTimeoutCoord.cancel(false);
                        tareaTimeoutCoord = planificador.schedule(this::venceTimeoutCoord,
                                timeoutCoordMs, TimeUnit.MILLISECONDS);
                    }
                } finally { lock.unlock(); }
                if (vigente) {
                    eventos.registrar(reloj.tick(), TipoEvento.ELECCION_OK,
                            "OK recibido de nodo " + msg.getNodoOrigen() + " (me retiro)");
                }
                break;

            case COORDINATOR:
                int s = msg.getNodoOrigen();
                boolean aceptado = false;
                lock.lock();
                try {
                    if (s >= coordinadorActual
                            || membresia.estado(coordinadorActual) == EstadoNodo.CAIDO) {
                        coordinadorActual = s;
                        enEleccion = false;
                        recibioOK = false;
                        cancelarTimers();
                        aceptado = true;
                    }
                } finally { lock.unlock(); }
                eventos.registrar(reloj.tick(), TipoEvento.ELECCION_COORDINADOR,
                        "COORDINATOR de nodo " + s
                                + (aceptado ? " -> nuevo coordinador" : " -> ignorado (stale)"));
                break;
        }
    }

    @Override
    public void onCambioEstado(int id, EstadoNodo previo, EstadoNodo nuevo) {
        if (nuevo == EstadoNodo.CAIDO && id == getCoordinador()) {
            eventos.registrar(reloj.valor(), TipoEvento.ELECCION_INICIO,
                    "cayó el coordinador " + id + ", inicio elección");
            iniciarEleccion();
        } else if (nuevo == EstadoNodo.VIVO && id > getCoordinador()) {
            eventos.registrar(reloj.valor(), TipoEvento.ELECCION_INICIO,
                    "reapareció nodo " + id + " (> coordinador), inicio elección");
            iniciarEleccion();
        }
    }

    private void enviar(MensajeEleccion msg, int idDestino) {
        poolEnvio.submit(() -> {
            mensajero.enviar(idDestino, msg);
            mensajesCoordinacion.increment();
        });
    }

    private void cancelarTimers() {
        if (tareaTimeoutOk != null) tareaTimeoutOk.cancel(false);
        if (tareaTimeoutCoord != null) tareaTimeoutCoord.cancel(false);
    }

    public long mensajesCoordinacion() { return mensajesCoordinacion.sum(); }

    public void detener() {
        planificador.shutdownNow();
        poolEnvio.shutdownNow();
    }
}
