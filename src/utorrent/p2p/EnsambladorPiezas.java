package utorrent.p2p;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import utorrent.modelos.Bloque;
import utorrent.utils.CalculadorHash;
import utorrent.utils.EscritorBloques;

/**
 * Representa un pedacito de una pieza. BitTorrent los baja de a 16 KB para
 * que podamos pedir partes de una misma pieza a distintos usuarios a la vez.
 */
public class EnsambladorPiezas {

    private final GestorPiezas gestorPiezas;
    private final EscritorBloques escritor;
    private final int longitudPieza;
    private final ConcurrentHashMap<Integer, BufferEnsamblado> buffers = new ConcurrentHashMap<>();

    public EnsambladorPiezas(GestorPiezas gestor, EscritorBloques escritor) {
        this.gestorPiezas = gestor;
        this.escritor = escritor;
        this.longitudPieza = gestor.getMetadatos().getLongitudPieza();
    }

    /**
     * Recibe un bloque. Si con este bloque se completa la pieza, ejecuta la
     * verificación y la escritura.
     *
     * @return true si la pieza se completó y verificó correctamente,
     *         false si la pieza aún está incompleta o si la verificación falló
     */
    public ResultadoBloque procesarBloque(Bloque bloque) throws IOException {
        int indicePieza = bloque.getIndicePieza();
        int longitudReal = gestorPiezas.longitudDePieza(indicePieza);

        BufferEnsamblado buffer = buffers.computeIfAbsent(
                indicePieza, k -> new BufferEnsamblado(longitudReal));

        boolean completo = buffer.agregarBloque(bloque);
        if (!completo) {
            return ResultadoBloque.PIEZA_INCOMPLETA;
        }

        // La pieza está completa: verificamos el hash
        byte[] datosPieza = buffer.consolidar();
        byte[] hashEsperado = gestorPiezas.hashEsperado(indicePieza);

        if (!CalculadorHash.verificar(datosPieza, hashEsperado)) {
            buffers.remove(indicePieza);
            return ResultadoBloque.HASH_INVALIDO;
        }

        // Hash válido
        long offsetGlobal = (long) indicePieza * longitudPieza;
        escritor.escribir(offsetGlobal, datosPieza);
        gestorPiezas.marcarCompletada(indicePieza);
        buffers.remove(indicePieza);

        return ResultadoBloque.PIEZA_VERIFICADA;
    }

    public void descartarPieza(int indicePieza) {
        buffers.remove(indicePieza);
    }

    public enum ResultadoBloque {
        PIEZA_INCOMPLETA,
        PIEZA_VERIFICADA,
        HASH_INVALIDO
    }

    private static class BufferEnsamblado {
        private final int longitudPieza;
        private final Map<Integer, byte[]> bloquesPorOffset = new HashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private int bytesAcumulados = 0;

        BufferEnsamblado(int longitudPieza) {
            this.longitudPieza = longitudPieza;
        }

        boolean agregarBloque(Bloque bloque) {
            lock.lock();
            try {
                if (bloquesPorOffset.containsKey(bloque.getOffset())) {
                    return bytesAcumulados >= longitudPieza;
                }
                bloquesPorOffset.put(bloque.getOffset(), bloque.getDatos());
                bytesAcumulados += bloque.getLongitud();
                return bytesAcumulados >= longitudPieza;
            } finally {
                lock.unlock();
            }
        }

        byte[] consolidar() {
            lock.lock();
            try {
                byte[] resultado = new byte[longitudPieza];
                for (Map.Entry<Integer, byte[]> e : bloquesPorOffset.entrySet()) {
                    int offset = e.getKey();
                    byte[] datos = e.getValue();
                    int aCopiar = Math.min(datos.length, longitudPieza - offset);
                    System.arraycopy(datos, 0, resultado, offset, aCopiar);
                }
                return resultado;
            } finally {
                lock.unlock();
            }
        }
    }
}