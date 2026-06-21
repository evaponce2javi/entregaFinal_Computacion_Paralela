package utorrent.p2p;

import java.util.HashSet;
import java.util.Set;

/**
 * Traduce el "bitfield" (un array de bytes) a una lista de piezas que ya tenemos.
 * BitTorrent empaqueta los bits de una forma un poco rara (el primer bit del primer 
 * byte es la pieza 0). Esta clase se encarga de ese lío para que el resto del 
 * programa solo vea números de pieza normales.
 * También se asegura de ignorar los bits que sobran al final si el total de 
 * piezas no es un múltiplo exacto de 8.
 */

public class GestorBitfield {

    public Set<Integer> parsear(byte[] bitfield, int totalPiezas) {
        Set<Integer> resultado = new HashSet<>();
        for (int i = 0; i < totalPiezas; i++) {
            int byteIdx = i / 8;
            int bitIdx  = 7 - (i % 8);
            if (byteIdx < bitfield.length && ((bitfield[byteIdx] >> bitIdx) & 1) == 1) {
                resultado.add(i);
            }
        }
        return resultado;
    }

    /**
     * Construye el bitfield binario a partir de los estados del GestorPiezas.
     */
    public byte[] construir(GestorPiezas gestor) {
        int total = gestor.totalPiezas();
        byte[] bf = new byte[(total + 7) / 8];
        for (int i = 0; i < total; i++) {
            if (gestor.tienePieza(i)) {
                int byteIdx = i / 8;
                int bitIdx  = 7 - (i % 8);
                bf[byteIdx] = (byte) (bf[byteIdx] | (1 << bitIdx));
            }
        }
        return bf;
    }
}