package utorrent.p2p;

import utorrent.modelos.InfoPar;
import utorrent.modelos.MetadatosTorrent;
import utorrent.utils.LectorBloques;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Se encarga de conectar con otros usuarios que nos pasó el tracker.
 * 
 * Lleva un registro de los IDs para no conectar dos veces al mismo 
 * usuario (por ejemplo, si el tracker lo repite o si él ya se 
 * conectó con nosotros antes).
 */
public class ClientePar {

    private static final int TIMEOUT_CONEXION_MS = 8_000;

    private final MetadatosTorrent meta;
    private final String miPeerId;
    private final GestorPiezas gestorPiezas;
    private final EnsambladorPiezas ensamblador;
    private final GestorChoke gestorChoke;
    private final LectorBloques lectorBloques;
    private final ServidorPar servidorPar;
    private final ExecutorService poolSesiones;

    private final ConcurrentHashMap<String, Boolean> peersConectados = new ConcurrentHashMap<>();

    public ClientePar(MetadatosTorrent meta, String miPeerId,
                      GestorPiezas gestorPiezas, EnsambladorPiezas ensamblador,
                      GestorChoke gestorChoke, LectorBloques lectorBloques,
                      ServidorPar servidorPar, ExecutorService poolSesiones) {
        this.meta = meta;
        this.miPeerId = miPeerId;
        this.gestorPiezas = gestorPiezas;
        this.ensamblador = ensamblador;
        this.gestorChoke = gestorChoke;
        this.lectorBloques = lectorBloques;
        this.servidorPar = servidorPar;
        this.poolSesiones = poolSesiones;
    }

    public void conectarA(InfoPar par) {
        if (par.getPeerId().equals(miPeerId)) return;
        if (peersConectados.putIfAbsent(par.getPeerId(), Boolean.TRUE) != null) {
            return;
        }

        poolSesiones.submit(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(par.getDireccionIp(), par.getPuerto()),
                        TIMEOUT_CONEXION_MS);

                SesionPar sesion = new SesionPar(
                        socket, meta, miPeerId,
                        gestorPiezas, ensamblador, gestorChoke,
                        lectorBloques, false);
                servidorPar.getSesionesActivas().add(sesion);
                try {
                    sesion.run();
                } finally {
                    servidorPar.getSesionesActivas().remove(sesion);
                }
            } catch (IOException e) {
                System.err.println("[ClientePar] No pude conectar a " + par
                        + ": " + e.getMessage());
            } finally {
                peersConectados.remove(par.getPeerId());
            }
        });
    }
}