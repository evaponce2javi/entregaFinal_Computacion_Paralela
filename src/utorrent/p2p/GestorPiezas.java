package utorrent.p2p;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import utorrent.modelos.EstadoPieza;
import utorrent.modelos.MetadatosTorrent;

/**
 * Controla qué piezas faltan y en qué estado están (bajando, listas o pendientes).
 * 
 * Se encarga de que dos hilos no intenten bajar la misma pieza a la vez. 
 * Si un usuario se desconecta o la pieza llega mal, la vuelve a marcar 
 * como pendiente para pedirla de nuevo.
 * Para elegir cuál bajar, simplemente agarra una al azar de las que 
 * tenga el otro usuario.
 */

public class GestorPiezas {

    private final MetadatosTorrent meta;
    private final AtomicReferenceArray<EstadoPieza> estados;
    private final java.util.Random rng = new java.util.Random();

    public GestorPiezas(MetadatosTorrent meta, boolean yaCompleto) {
        this.meta = meta;
        this.estados = new AtomicReferenceArray<>(meta.totalPiezas());
        for (int i = 0; i < estados.length(); i++) {
            estados.set(i, yaCompleto ? EstadoPieza.COMPLETADA : EstadoPieza.PENDIENTE);
        }
    }

        /**
     * Busca una pieza que nos falte y que el otro usuario tenga. 
     * La marca como "ocupada" para que nadie más la pida; si justo otro hilo 
     * nos ganó de mano, sigue buscando la siguiente.
     *
     * @return el número de pieza para bajar, o -1 si no hay nada que nos sirva.
     */
    public int seleccionarSiguientePieza(Set<Integer> piezasDelPeer) {
        List<Integer> candidatas = new ArrayList<>();
        for (int idx : piezasDelPeer) {
            if (estados.get(idx) == EstadoPieza.PENDIENTE) {
                candidatas.add(idx);
            }
        }
        if (candidatas.isEmpty()) return -1;

        Collections.shuffle(candidatas, rng);
        for (int idx : candidatas) {
            if (estados.compareAndSet(idx, EstadoPieza.PENDIENTE, EstadoPieza.EN_CURSO)) {
                return idx;
            }
        }
        return -1;
    }

    /** Marca como completada. Solo se llama tras verificación SHA-1. */
    public void marcarCompletada(int indicePieza) {
        estados.set(indicePieza, EstadoPieza.COMPLETADA);
    }

    /**
     * Pone una pieza otra vez en la lista de pendientes. 
     * Se usa si el hash dio mal, si se cortó la conexión o si 
     * el otro usuario tardó demasiado en responder.
     */
    public void reencolar(int indicePieza) {
        estados.compareAndSet(indicePieza, EstadoPieza.EN_CURSO, EstadoPieza.PENDIENTE);
    }

    public boolean tienePieza(int indicePieza) {
        return estados.get(indicePieza) == EstadoPieza.COMPLETADA;
    }

    public boolean estaCompleto() {
        for (int i = 0; i < estados.length(); i++) {
            if (estados.get(i) != EstadoPieza.COMPLETADA) return false;
        }
        return true;
    }

    public int totalPiezas() { return meta.totalPiezas(); }

    public int piezasCompletadas() {
        int n = 0;
        for (int i = 0; i < estados.length(); i++) {
            if (estados.get(i) == EstadoPieza.COMPLETADA) n++;
        }
        return n;
    }

    public byte[] hashEsperado(int indicePieza) {
        return meta.getHashesDePiezas().get(indicePieza);
    }

    public int longitudDePieza(int indicePieza) {
        return meta.longitudPiezaEn(indicePieza);
    }

    public MetadatosTorrent getMetadatos() { return meta; }
}