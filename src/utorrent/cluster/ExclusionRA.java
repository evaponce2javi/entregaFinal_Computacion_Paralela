package utorrent.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exclusión mutua distribuida — algoritmo de Ricart-Agrawala (Unidad 6, req. 2.3).
 *
 * Protege la escritura al directorio replicado (recurso crítico compartido):
 * solo un nodo del clúster muta el directorio a la vez, lo que da un orden total
 * a las escrituras de F1 y cierra la consistencia eventual del Paso 3.
 *
 * Protocolo:
 *  - Entrar: el nodo sella su petición con (ts, id) de Lamport y hace multicast
 *    REQUEST a los demás nodos alcanzables. Entra a la SC cuando recibe REPLY de
 *    todos ellos.
 *  - Al recibir un REQUEST ajeno: responde REPLY de inmediato, salvo que esté en
 *    la SC, o esté pidiendo con MAYOR prioridad — en cuyo caso difiere el REPLY
 *    hasta salir de su SC. La prioridad es el orden lexicográfico de (ts, id).
 *
 * Tolerancia a fallos (clave para 2.4 y 4.9):
 *  - Si el REQUEST a un nodo falla en el envío (caído/inalcanzable), ese nodo se
 *    descuenta del quórum de inmediato.
 *  - Si un nodo se declara CAIDO mientras se espera su REPLY, onCambioEstado lo
 *    quita de los pendientes y desbloquea la entrada: una caída no deja el mutex
 *    colgado.
 *  - Un timeout de seguridad evita el bloqueo indefinido ante omisión de
 *    mensajes (último recurso, se registra como advertencia).
 *
 * Concurrencia:
 *  - localCs (justo/FIFO) serializa a los hilos locales: un solo escritor local
 *    corre el protocolo a la vez.
 *  - estado (+ su Condition) protege el estado compartido con los hilos que
 *    reciben REQUEST/REPLY. Los receptores solo toman 'estado', nunca 'localCs',
 *    de modo que no hay inversión de orden de locks. Ningún lock se mantiene
 *    mientras se hace E/S de red (los envíos van a un pool aparte).
 */
public final class ExclusionRA implements OyenteMembresia {

    private static final long TIMEOUT_SEGURIDAD_MS = 15_000;

    private final Membresia membresia;
    private final RelojLamport reloj;
    private final RegistroEventos eventos;
    private final Mensajero mensajero;

    private final ReentrantLock localCs = new ReentrantLock(true);
    private final ReentrantLock estado = new ReentrantLock();
    private final Condition puedeEntrar = estado.newCondition();

    private boolean solicitando = false;
    private boolean enSC = false;
    private long miTs = 0;
    private final Set<Integer> pendientes = new HashSet<>();
    private final Set<Integer> diferidos = new HashSet<>();

    private final ExecutorService poolEnvio;
    private final LongAdder mensajesCoordinacion = new LongAdder();

    public ExclusionRA(Membresia membresia, RelojLamport reloj,
                       RegistroEventos eventos, Mensajero mensajero) {
        this.membresia = membresia;
        this.reloj = reloj;
        this.eventos = eventos;
        this.mensajero = mensajero;
        AtomicInteger n = new AtomicInteger(1);
        this.poolEnvio = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ra-envio-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        membresia.registrarOyente(this);
    }

    /** Adquiere la sección crítica distribuida (bloqueante). */
    public void entrarSC() {
        localCs.lock();
        final long ts;
        final List<Integer> objetivo;
        estado.lock();
        try {
            ts = reloj.tick();
            miTs = ts;
            solicitando = true;
            objetivo = membresia.paresAlcanzables();
            pendientes.clear();
            pendientes.addAll(objetivo);
        } finally {
            estado.unlock();
        }
        eventos.registrar(ts, TipoEvento.MUTEX_REQUEST,
                "REQUEST (ts=" + ts + ") a " + objetivo.size() + " nodo(s) " + objetivo);

        for (int id : objetivo) {
            final int destino = id;
            poolEnvio.submit(() -> {
                boolean ok = mensajero.enviar(destino, MensajeMutex.request(ts, membresia.idLocal()));
                mensajesCoordinacion.increment();
                if (!ok) descartarPendiente(destino);
            });
        }

        boolean degradado = false;
        estado.lock();
        try {
            long restanteNs = TimeUnit.MILLISECONDS.toNanos(TIMEOUT_SEGURIDAD_MS);
            while (!pendientes.isEmpty() && restanteNs > 0) {
                restanteNs = puedeEntrar.awaitNanos(restanteNs);
            }
            degradado = !pendientes.isEmpty();
            enSC = true;
            solicitando = false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            estado.unlock();
        }
        if (degradado) {
            eventos.registrar(reloj.valor(), TipoEvento.MUTEX_ENTRA_SC,
                    "ADVERTENCIA: timeout de seguridad, entro con pendientes sin responder");
        }
        eventos.registrar(reloj.tick(), TipoEvento.MUTEX_ENTRA_SC, "entra a la SC");
    }

    /** Libera la sección crítica distribuida. */
    public void salirSC() {
        final List<Integer> aResponder;
        final long ts;
        estado.lock();
        try {
            enSC = false;
            aResponder = new ArrayList<>(diferidos);
            diferidos.clear();
            ts = reloj.tick();
        } finally {
            estado.unlock();
        }
        eventos.registrar(ts, TipoEvento.MUTEX_SALE_SC,
                "sale de la SC, REPLY diferidos a " + aResponder);
        for (int id : aResponder) {
            enviarReply(id);
        }
        localCs.unlock();
    }

    /** Procesa un mensaje de Ricart-Agrawala recibido de otro nodo. */
    public void recibir(MensajeMutex msg) {
        long ts = reloj.actualizar(msg.getSelloLamport());

        if (msg.getTipo() == MensajeMutex.Tipo.REPLY) {
            estado.lock();
            try {
                pendientes.remove(msg.getNodoOrigen());
                if (pendientes.isEmpty()) puedeEntrar.signalAll();
            } finally {
                estado.unlock();
            }
            eventos.registrar(ts, TipoEvento.MUTEX_REPLY,
                    "REPLY recibido de nodo " + msg.getNodoOrigen());
            return;
        }

        // REQUEST
        boolean otorgar;
        estado.lock();
        try {
            if (enSC) {
                otorgar = false;
            } else if (solicitando) {
                otorgar = tienePrioridad(msg.getSelloLamport(), msg.getNodoOrigen(),
                                         miTs, membresia.idLocal());
            } else {
                otorgar = true;
            }
            if (!otorgar) diferidos.add(msg.getNodoOrigen());
        } finally {
            estado.unlock();
        }
        eventos.registrar(ts, TipoEvento.MUTEX_REQUEST,
                "REQUEST de nodo " + msg.getNodoOrigen() + " -> " + (otorgar ? "REPLY" : "diferido"));
        if (otorgar) enviarReply(msg.getNodoOrigen());
    }

    /** true si (tsA,idA) tiene mayor prioridad (es lexicográficamente menor) que (tsB,idB). */
    private static boolean tienePrioridad(long tsA, int idA, long tsB, int idB) {
        if (tsA != tsB) return tsA < tsB;
        return idA < idB;
    }

    private void enviarReply(int idDestino) {
        long ts = reloj.tick();
        poolEnvio.submit(() -> {
            mensajero.enviar(idDestino, MensajeMutex.reply(ts, membresia.idLocal()));
            mensajesCoordinacion.increment();
        });
    }

    private void descartarPendiente(int id) {
        estado.lock();
        try {
            if (pendientes.remove(id) && pendientes.isEmpty()) {
                puedeEntrar.signalAll();
            }
        } finally {
            estado.unlock();
        }
    }

    /** Membership-aware: una caída no debe dejar el mutex esperando por siempre. */
    @Override
    public void onCambioEstado(int id, EstadoNodo previo, EstadoNodo nuevo) {
        if (nuevo != EstadoNodo.CAIDO) return;
        boolean descontado;
        estado.lock();
        try {
            diferidos.remove(id);
            descontado = pendientes.remove(id);
            if (descontado && pendientes.isEmpty()) puedeEntrar.signalAll();
        } finally {
            estado.unlock();
        }
        if (descontado) {
            eventos.registrar(reloj.valor(), TipoEvento.FALLO_CAIDA,
                    "nodo " + id + " CAIDO: descontado del quórum del mutex");
        }
    }

    public long mensajesCoordinacion() { return mensajesCoordinacion.sum(); }

    public void detener() { poolEnvio.shutdownNow(); }
}
