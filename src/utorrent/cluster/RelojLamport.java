package utorrent.cluster;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reloj lógico de Lamport (Unidad 5, req. 2.2).
 *
 * Implementa las tres reglas clásicas:
 *  - Evento local / sello de un mensaje saliente:  L = L + 1          -> tick()
 *  - Al recibir un mensaje con sello s:            L = max(L, s) + 1  -> actualizar(s)
 *
 * Garantiza la propiedad de Lamport: si a -> b (a sucede-antes de b), entonces
 * L(a) < L(b). Eso da un orden total consistente con la causalidad —
 * desempatando por id de nodo cuando dos sellos coinciden— que es lo que usan
 * Ricart-Agrawala para priorizar la sección crítica (Paso 4) y el informe para
 * mostrar el ordenamiento causal de la función de directorio F1.
 *
 * Concurrencia: un único AtomicLong con operaciones read-modify-write atómicas
 * (incrementAndGet / updateAndGet). Es lock-free y correcto aunque varios hilos
 * —handlers del tracker, detector de fallos, canal de réplica— lo toquen a la
 * vez: el cuello clave es que max(L,s)+1 se aplique de forma indivisible, cosa
 * que updateAndGet garantiza.
 */
public final class RelojLamport {

    private final AtomicLong reloj;

    public RelojLamport()            { this(0L); }
    public RelojLamport(long inicio) { this.reloj = new AtomicLong(inicio); }

    /** Evento local o sello para un mensaje saliente: incrementa y devuelve. */
    public long tick() {
        return reloj.incrementAndGet();
    }

    /** Recepción de un mensaje: L = max(L, selloRecibido) + 1, atómico. */
    public long actualizar(long selloRecibido) {
        return reloj.updateAndGet(actual -> Math.max(actual, selloRecibido) + 1);
    }

    /** Valor actual sin modificarlo (solo para inspección/log). */
    public long valor() {
        return reloj.get();
    }
}
