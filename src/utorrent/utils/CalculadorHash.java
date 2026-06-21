package utorrent.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Operaciones criptográficas SHA-1 del protocolo BitTorrent.
 * Generamos los hashes en memoria al hacer seeding inicial, sin recurrir a un 
 * .torrent en disco.
 */
public class CalculadorHash {

    private static final int TAMANO_HASH = 20;

    private CalculadorHash() { /* utilidad */ }

    public static byte[] sha1(byte[] datos) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(datos);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 no disponible en la JVM", e);
        }
    }

    /**
     * Lee un archivo y produce el hash SHA-1 de cada pieza, en orden.
     */
    public static List<byte[]> hashearArchivoPorPiezas(Path archivo, int longitudPieza)
            throws IOException {
        List<byte[]> hashes = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(archivo.toFile(), "r")) {
            long longitud = raf.length();
            long offset = 0;
            byte[] buffer = new byte[longitudPieza];

            while (offset < longitud) {
                int aLeer = (int) Math.min(longitudPieza, longitud - offset);
                raf.seek(offset);
                raf.readFully(buffer, 0, aLeer);

                byte[] datosReales = (aLeer == longitudPieza)
                        ? buffer
                        : java.util.Arrays.copyOf(buffer, aLeer);
                hashes.add(sha1(datosReales));

                offset += aLeer;
            }
        }
        return hashes;
    }

    /**
     * Calcula el infoHash del torrent: SHA-1 sobre la concatenación de los
     * campos identificadores.
     */
    public static byte[] calcularInfoHash(String nombreArchivo, long longitudTotal,
                                          int longitudPieza, List<byte[]> hashesPiezas) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(nombreArchivo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(longBytes(longitudTotal));
            md.update(intBytes(longitudPieza));
            for (byte[] h : hashesPiezas) md.update(h);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 no disponible en la JVM", e);
        }
    }

    /**
     * Verifica si los datos coinciden con el hash esperado.
     */
    public static boolean verificar(byte[] datos, byte[] hashEsperado) {
        if (hashEsperado == null || hashEsperado.length != TAMANO_HASH) return false;
        return MessageDigest.isEqual(sha1(datos), hashEsperado);
    }

    // helpers
    private static byte[] longBytes(long v) {
        return new byte[]{
                (byte)(v >>> 56), (byte)(v >>> 48), (byte)(v >>> 40), (byte)(v >>> 32),
                (byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8),  (byte) v
        };
    }

    private static byte[] intBytes(int v) {
        return new byte[]{
                (byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8), (byte) v
        };
    }

    public static String aHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}