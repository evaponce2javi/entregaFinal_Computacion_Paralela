package utorrent.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Escritura aleatoria de bloques en un archivo de salida. Usado por el Leecher.
 */
public class EscritorBloques implements AutoCloseable {

    private final RandomAccessFile raf;
    private final ReentrantLock lock = new ReentrantLock();
    private final Path destino;

    public EscritorBloques(Path destino, long longitudTotal) throws IOException {
        this.destino = destino;
        Files.createDirectories(destino.getParent() != null
                ? destino.getParent() : Path.of("."));
        this.raf = new RandomAccessFile(destino.toFile(), "rw");
        this.raf.setLength(longitudTotal);
    }

    public void escribir(long offsetGlobal, byte[] datos) throws IOException {
        lock.lock();
        try {
            raf.seek(offsetGlobal);
            raf.write(datos);
        } finally {
            lock.unlock();
        }
    }

    public void sincronizar() throws IOException {
        lock.lock();
        try { raf.getFD().sync(); }
        finally { lock.unlock(); }
    }

    public Path getDestino() { return destino; }

    @Override
    public void close() throws IOException {
        lock.lock();
        try { raf.close(); }
        finally { lock.unlock(); }
    }
}