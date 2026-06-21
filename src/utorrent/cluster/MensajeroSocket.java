package utorrent.cluster;

/**
 * Mensajero real: resuelve el id de destino a su InfoNodo mediante la membresía
 * y envía por socket con Transporte.
 */
public final class MensajeroSocket implements Mensajero {

    private final Membresia membresia;

    public MensajeroSocket(Membresia membresia) {
        this.membresia = membresia;
    }

    @Override
    public boolean enviar(int idDestino, Object msg) {
        InfoNodo destino = membresia.infoDe(idDestino);
        if (destino == null) return false;
        return Transporte.enviar(destino, msg);
    }
}
