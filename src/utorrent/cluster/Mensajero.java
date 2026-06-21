package utorrent.cluster;

/**
 * Abstracción de envío de mensajes de coordinación a un nodo identificado por
 * id. Desacopla los algoritmos (Ricart-Agrawala, y luego Bully) del transporte
 * concreto: en producción se inyecta un mensajero por sockets; en las pruebas,
 * uno en memoria que permite verificar la exclusión mutua de forma determinista
 * en un solo proceso.
 */
public interface Mensajero {

    /** @return true si el mensaje se entregó al transporte sin error. */
    boolean enviar(int idDestino, Object msg);
}
