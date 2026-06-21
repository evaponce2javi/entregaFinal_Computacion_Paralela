package utorrent.modelos;

/**
 * Los tres estados por los que pasa una pieza mientras se descarga.
 */
public enum EstadoPieza {
    PENDIENTE, //todavia no se ha pedido a nadie
    EN_CURSO, //alguien la esta bajando ahora
    COMPLETADA // ya esta verificada y guardada en disco
}