package utorrent.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lectura aleatoria de bloques desde un archivo. Usado por el Seeder para
 * responder mensajes "request" sin cargar el archivo completo en memoria.
 */
public class LectorBloques implements AutoCloseable {

    private final RandomAccessFile raf;
    private final ReentrantLock lock = new ReentrantLock();
    private final long longitudTotal;

    public LectorBloques(Path archivo) throws IOException {
        this.raf = new RandomAccessFile(archivo.toFile(), "r");
        this.longitudTotal = raf.length();
    }

    public byte[] leer(long offsetGlobal, int longitud) throws IOException {
        if (offsetGlobal < 0 || offsetGlobal >= longitudTotal) {
            throw new IOException("Offset fuera de rango: " + offsetGlobal);
        }
        int aLeer = (int) Math.min(longitud, longitudTotal - offsetGlobal);
        byte[] buffer = new byte[aLeer];

        lock.lock();
        try {
            raf.seek(offsetGlobal);
            raf.readFully(buffer);
        } finally {
            lock.unlock();
        }
        return buffer;
    }

    public long getLongitudTotal() { return longitudTotal; }

    @Override
    public void close() throws IOException {
        lock.lock();
        try { raf.close(); }
        finally { lock.unlock(); }
    }
}