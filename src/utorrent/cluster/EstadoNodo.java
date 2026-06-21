package utorrent.cluster;

/**
 * Estado de un nodo del clúster según el detector de fallos (Unidad 6, req. 2.4).
 *
 *  VIVO       - responde heartbeats con normalidad.
 *  SOSPECHOSO - perdió algún latido pero aún no supera el umbral de caída.
 *               También es el estado inicial de los pares durante el periodo
 *               de gracia de arranque: así no declaramos caído a un nodo que
 *               todavía no termina de levantar (evita elecciones espurias y el
 *               falso positivo de bootstrap descrito en el plan).
 *  CAIDO      - superó el umbral de latidos perdidos. Dispara la reconfiguración:
 *               re-elección Bully y recálculo del quórum de Ricart-Agrawala.
 */
public enum EstadoNodo {
    VIVO,
    SOSPECHOSO,
    CAIDO
}
