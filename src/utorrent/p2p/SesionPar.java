package utorrent.p2p;

import utorrent.modelos.Bloque;
import utorrent.modelos.MetadatosTorrent;
import utorrent.protocolo.MensajeBitfield;
import utorrent.protocolo.MensajeHandshake;
import utorrent.protocolo.MensajeHave;
import utorrent.protocolo.MensajeInterested;
import utorrent.protocolo.MensajePeer;
import utorrent.protocolo.MensajePiece;
import utorrent.protocolo.MensajeRequest;
import utorrent.protocolo.MensajeUnchoke;
import utorrent.utils.LectorBloques;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maneja la charla con otro usuario de principio a fin. Cada conexión corre 
 * en su propio hilo y sigue estos pasos:
 * 
 * 1. Saludo inicial: Validamos que ambos estemos bajando lo mismo.
 * 2. Intercambio de mapas: Vemos qué piezas tiene cada uno.
 * 3. Interés: Le avisamos si tiene algo que nos sirve.
 * 4. Espera: Aguardamos a que nos deje pedirle cosas.
 * 5. Bucle principal: Leemos mensajes constantemente. Si no pasa nada en 
 *    un buen rato o terminamos de bajar todo, cerramos la conexión.
 * 6. Subida: Si él nos pide algo y tenemos permiso, le mandamos los bloques.
 * 
 * Si hay un error de red, el otro desaparece o nos manda datos corruptos, 
 * cortamos la conexión y limpiamos todo.
 */
public class SesionPar implements Runnable {

    private static final int TAMANO_BLOQUE = 16_384;

    /** Timeout corto*/
    private static final int TIMEOUT_LECTURA_MS = 5_000;

    private static final long INACTIVIDAD_MAX_MS = 90_000L;

    private final Socket socket;
    private final MetadatosTorrent meta;
    private final String miPeerId;
    private final GestorPiezas gestorPiezas;
    private final EnsambladorPiezas ensamblador;
    private final GestorChoke gestorChoke;
    private final LectorBloques lectorBloques;
    private final boolean entrante;

    private final GestorBitfield gestorBitfield = new GestorBitfield();
    private final AtomicBoolean activo = new AtomicBoolean(true);

    private String peerIdRemoto;
    private Set<Integer> piezasDelRemoto = new HashSet<>();
    private boolean leEstoyChokeando = true;
    private boolean elMeTieneChokeado = true;
    private boolean leDijeInterested = false;
    private boolean elMeDijoInterested = false;

    private int piezaEnCurso = -1;
    private int siguienteOffsetEsperado = 0;
    private long ultimaActividadMs = System.currentTimeMillis();

    private DataInputStream entrada;
    private DataOutputStream salida;

    public SesionPar(Socket socket, MetadatosTorrent meta, String miPeerId,
                     GestorPiezas gestorPiezas, EnsambladorPiezas ensamblador,
                     GestorChoke gestorChoke, LectorBloques lectorBloques,
                     boolean entrante) {
        this.socket = socket;
        this.meta = meta;
        this.miPeerId = miPeerId;
        this.gestorPiezas = gestorPiezas;
        this.ensamblador = ensamblador;
        this.gestorChoke = gestorChoke;
        this.lectorBloques = lectorBloques;
        this.entrante = entrante;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(TIMEOUT_LECTURA_MS);
            this.entrada = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.salida = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            ejecutarHandshake();
            enviarBitfield();
            recibirBitfield();
            evaluarInteresInicial();

            if (gestorChoke.estaUnchoked(peerIdRemoto) && leEstoyChokeando) {
                new MensajeUnchoke().escribirEn(salida);
                leEstoyChokeando = false;
            }

            buclePrincipal();

        } catch (SocketTimeoutException ste) {
            System.err.println("[SesionPar] Timeout con " + abreviar(peerIdRemoto)
                    + ": " + ste.getMessage());
        } catch (SocketException | EOFException e) {
            System.err.println("[SesionPar] Peer " + abreviar(peerIdRemoto)
                    + " desconectado: " + (e.getMessage() == null ? "EOF" : e.getMessage()));
        } catch (IOException e) {
            System.err.println("[SesionPar] IO con " + abreviar(peerIdRemoto) + ": " + e);
        } catch (Exception e) {
            System.err.println("[SesionPar] Error con " + abreviar(peerIdRemoto) + ": " + e);
        } finally {
            cerrar();
        }
    }

    private void ejecutarHandshake() throws IOException {
        if (entrante) {
            MensajeHandshake suyo = MensajeHandshake.leerDe(entrada);
            if (!java.util.Arrays.equals(suyo.getInfoHash(), meta.getInfoHash())) {
                throw new IOException("Handshake con infoHash distinto al esperado");
            }
            this.peerIdRemoto = suyo.getPeerIdComoString();
            new MensajeHandshake(meta.getInfoHash(),
                    miPeerId.getBytes(StandardCharsets.US_ASCII)).escribirEn(salida);
        } else {
            new MensajeHandshake(meta.getInfoHash(),
                    miPeerId.getBytes(StandardCharsets.US_ASCII)).escribirEn(salida);
            MensajeHandshake suyo = MensajeHandshake.leerDe(entrada);
            if (!java.util.Arrays.equals(suyo.getInfoHash(), meta.getInfoHash())) {
                throw new IOException("Handshake con infoHash distinto al esperado");
            }
            this.peerIdRemoto = suyo.getPeerIdComoString();
        }
        gestorChoke.registrarPar(peerIdRemoto);
        System.out.println("[SesionPar] Handshake OK con " + abreviar(peerIdRemoto));
    }

    private void enviarBitfield() throws IOException {
        byte[] bf = gestorBitfield.construir(gestorPiezas);
        new MensajeBitfield(bf).escribirEn(salida);
    }

    private void recibirBitfield() throws IOException {
        MensajePeer primero = MensajePeer.leerSiguiente(entrada);
        if (primero instanceof MensajeBitfield) {
            byte[] bf = ((MensajeBitfield) primero).getBitfield();
            piezasDelRemoto = gestorBitfield.parsear(bf, meta.totalPiezas());
            System.out.println("[SesionPar] " + abreviar(peerIdRemoto)
                    + " tiene " + piezasDelRemoto.size() + "/" + meta.totalPiezas() + " piezas");
        } else {
            piezasDelRemoto = new HashSet<>();
            if (primero != null) procesarMensaje(primero);
        }
    }

    private void evaluarInteresInicial() throws IOException {
        boolean tienePiezasUtiles = false;
        for (int idx : piezasDelRemoto) {
            if (!gestorPiezas.tienePieza(idx)) { tienePiezasUtiles = true; break; }
        }
        if (tienePiezasUtiles) {
            new MensajeInterested().escribirEn(salida);
            leDijeInterested = true;
        }
    }

    private void buclePrincipal() throws IOException {
        while (activo.get() && !socket.isClosed()) {

            if (!elMeTieneChokeado && leDijeInterested) {
                avanzarDescarga();
            }

            try {
                MensajePeer msg = MensajePeer.leerSiguiente(entrada);
                ultimaActividadMs = System.currentTimeMillis();
                if (msg != null) {
                    procesarMensaje(msg);
                }
            } catch (SocketTimeoutException ste) {
                long inactivoMs = System.currentTimeMillis() - ultimaActividadMs;

                if (gestorPiezas.estaCompleto() && piezaEnCurso == -1
                        && !elMeDijoInterested) {
                    System.out.println("[SesionPar] Sin trabajo pendiente con "
                            + abreviar(peerIdRemoto) + "; cerrando.");
                    return;
                }

                if (inactivoMs > INACTIVIDAD_MAX_MS) {
                    System.out.println("[SesionPar] Inactividad de " + inactivoMs
                            + " ms con " + abreviar(peerIdRemoto) + "; cerrando.");
                    return;
                }
            }
        }
    }

    private void avanzarDescarga() throws IOException {
        if (piezaEnCurso == -1) {
            int idx = gestorPiezas.seleccionarSiguientePieza(piezasDelRemoto);
            if (idx == -1) return;
            piezaEnCurso = idx;
            siguienteOffsetEsperado = 0;
        }

        int longitudPieza = gestorPiezas.longitudDePieza(piezaEnCurso);
        if (siguienteOffsetEsperado >= longitudPieza) return;
        int longitudBloque = Math.min(TAMANO_BLOQUE, longitudPieza - siguienteOffsetEsperado);
        new MensajeRequest(piezaEnCurso, siguienteOffsetEsperado, longitudBloque)
                .escribirEn(salida);
        siguienteOffsetEsperado += longitudBloque;
    }

    private void procesarMensaje(MensajePeer msg) throws IOException {
        switch (msg.getId()) {
            case 0:  procesarChoke(); break;
            case 1:  procesarUnchoke(); break;
            case 2:  procesarInterested(); break;
            case 3:  procesarNotInterested(); break;
            case 4:  procesarHave((MensajeHave) msg); break;
            case 5:
                System.err.println("[SesionPar] Bitfield fuera de tiempo de "
                        + abreviar(peerIdRemoto) + " — cerrando");
                activo.set(false);
                break;
            case 6:  procesarRequest((MensajeRequest) msg); break;
            case 7:  procesarPiece((MensajePiece) msg); break;
            default: break;
        }
    }

    private void procesarChoke() {
        elMeTieneChokeado = true;
        if (piezaEnCurso != -1) {
            gestorPiezas.reencolar(piezaEnCurso);
            ensamblador.descartarPieza(piezaEnCurso);
            piezaEnCurso = -1;
            siguienteOffsetEsperado = 0;
        }
    }

    private void procesarUnchoke() throws IOException {
        elMeTieneChokeado = false;
        if (leDijeInterested) avanzarDescarga();
    }

    private void procesarInterested() throws IOException {
        elMeDijoInterested = true;
        if (gestorChoke.estaUnchoked(peerIdRemoto) && leEstoyChokeando) {
            new MensajeUnchoke().escribirEn(salida);
            leEstoyChokeando = false;
        }
    }

    private void procesarNotInterested() {
        elMeDijoInterested = false;
    }

    private void procesarHave(MensajeHave have) {
        piezasDelRemoto.add(have.getIndicePieza());
        if (!leDijeInterested && !gestorPiezas.tienePieza(have.getIndicePieza())) {
            try {
                new MensajeInterested().escribirEn(salida);
                leDijeInterested = true;
            } catch (IOException e) {
                System.err.println("[SesionPar] No pude enviar interested: " + e.getMessage());
            }
        }
    }

    private void procesarRequest(MensajeRequest req) throws IOException {
        if (leEstoyChokeando) return;
        if (!gestorPiezas.tienePieza(req.getIndicePieza())) return;
        if (lectorBloques == null) return;

        long offsetGlobal = (long) req.getIndicePieza() * meta.getLongitudPieza()
                + req.getBegin();
        byte[] datos = lectorBloques.leer(offsetGlobal, req.getLongitud());
        new MensajePiece(req.getIndicePieza(), req.getBegin(), datos).escribirEn(salida);
    }

    private void procesarPiece(MensajePiece piece) throws IOException {
        if (piece.getIndicePieza() != piezaEnCurso) return;

        Bloque bloque = new Bloque(
                piece.getIndicePieza(), piece.getBegin(), piece.getDatos());
        EnsambladorPiezas.ResultadoBloque resultado = ensamblador.procesarBloque(bloque);

        switch (resultado) {
            case PIEZA_INCOMPLETA:
                avanzarDescarga();
                break;
            case PIEZA_VERIFICADA:
                gestorChoke.registrarBytesDescargados(peerIdRemoto, piece.getDatos().length);
                System.out.printf("[SesionPar] ✓ Pieza %d/%d verificada (%d/%d total)%n",
                        piezaEnCurso, meta.totalPiezas(),
                        gestorPiezas.piezasCompletadas(), meta.totalPiezas());
                piezaEnCurso = -1;
                siguienteOffsetEsperado = 0;
                break;
            case HASH_INVALIDO:
                System.err.println("[SesionPar] ✗ Falla de VALOR en pieza "
                        + piezaEnCurso + " desde " + abreviar(peerIdRemoto));
                gestorPiezas.reencolar(piezaEnCurso);
                piezaEnCurso = -1;
                siguienteOffsetEsperado = 0;
                break;
        }
    }

    public String getPeerIdRemoto() { return peerIdRemoto; }

    public void detener() { activo.set(false); }

    public synchronized void notificarHave(int indicePieza) {
        try {
            if (salida != null && !socket.isClosed()) {
                new MensajeHave(indicePieza).escribirEn(salida);
            }
        } catch (IOException e) {
        }
    }

    private void cerrar() {
        activo.set(false);
        if (peerIdRemoto != null) gestorChoke.desregistrarPar(peerIdRemoto);
        if (piezaEnCurso != -1) {
            gestorPiezas.reencolar(piezaEnCurso);
            ensamblador.descartarPieza(piezaEnCurso);
        }
        try { socket.close(); } catch (IOException ignorada) {}
    }

    private static String abreviar(String id) {
        if (id == null) return "?";
        return id.substring(0, Math.min(8, id.length()));
    }
}