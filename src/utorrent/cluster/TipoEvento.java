package utorrent.cluster;

/**
 * Vocabulario de eventos relevantes que se registran con su marca de Lamport.
 *
 * Tipar las categorías permite filtrar el log/CSV al armar la tabla y los
 * gráficos del informe (req. 2.2 y 3.4): orden causal de F1, mensajes de
 * coordinación, eventos de elección y detección de la falla inducida.
 *
 * Las categorías marcadas con su paso se empiezan a emitir cuando ese paso se
 * implemente; aquí se declaran para fijar el vocabulario desde el principio.
 */
public enum TipoEvento {
    LOCAL,                  // evento interno sin mensaje
    DIRECTORIO_ESCRITURA,   // mutación de la función F1 (registro de peer / metadatos)
    REPLICA_ENVIO,          // Paso 3: replicación inter-tracker
    REPLICA_RECEPCION,
    MUTEX_REQUEST,          // Paso 4: Ricart-Agrawala
    MUTEX_REPLY,
    MUTEX_ENTRA_SC,
    MUTEX_SALE_SC,
    HEARTBEAT,              // Paso 5: detección de fallos
    FALLO_SOSPECHA,
    FALLO_CAIDA,
    ELECCION_INICIO,        // Paso 6: elección Bully
    ELECCION_OK,
    ELECCION_COORDINADOR,
    METRICAS                // Paso 8: muestreo periódico de métricas del clúster
}
