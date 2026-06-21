package utorrent.cluster;

/**
 * Observador de cambios en la vista de membresía.
 *
 * Lo implementarán (en pasos posteriores):
 *  - el gestor de exclusión mutua Ricart-Agrawala, para liberar la espera por
 *    el REPLY de un nodo que cae y recalcular el quórum sobre los VIVOS;
 *  - el algoritmo de elección Bully, para dispararse cuando se detecta que el
 *    coordinador actual pasó a CAIDO.
 *
 * El contrato es deliberadamente mínimo para no acoplar la membresía a la
 * lógica de cada algoritmo.
 */
public interface OyenteMembresia {

    /**
     * Se invoca cuando el estado de un nodo cambia efectivamente.
     *
     * @param id     nodo cuyo estado cambió
     * @param previo estado anterior
     * @param nuevo  estado nuevo
     */
    void onCambioEstado(int id, EstadoNodo previo, EstadoNodo nuevo);
}
