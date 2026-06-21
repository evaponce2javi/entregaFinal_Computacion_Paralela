package utorrent.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validaciones de tamaño y existencia para archivos a compartir.
 */
public class LimitesArchivo {

    public static final long TAMANO_MAXIMO_BYTES = 50L * 1024L * 1024L; // 50 MB

    private LimitesArchivo() { /* utilidad */ }

    public static void validarParaSeeding(Path archivo) throws IOException {
        if (!Files.exists(archivo)) {
            throw new IOException("El archivo no existe: " + archivo);
        }
        if (!Files.isReadable(archivo)) {
            throw new IOException("El archivo no es legible: " + archivo);
        }
        long tamano = Files.size(archivo);
        if (tamano > TAMANO_MAXIMO_BYTES) {
            throw new IllegalArgumentException(String.format(
                    "Archivo demasiado grande: %d bytes (máximo permitido: %d bytes / 50 MB)",
                    tamano, TAMANO_MAXIMO_BYTES));
        }
        if (tamano == 0) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }
    }
}