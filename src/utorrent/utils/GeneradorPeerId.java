package utorrent.utils;

import java.security.SecureRandom;

/**
 * Genera peer IDs de 20 bytes según convención Azureus.
 */
public class GeneradorPeerId {

    private static final String PREFIJO = "-UT0001-";
    private static final String CHARSET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom rng = new SecureRandom();

    public static String generar() {
        StringBuilder sb = new StringBuilder(20);
        sb.append(PREFIJO);
        for (int i = 0; i < 20 - PREFIJO.length(); i++) {
            sb.append(CHARSET.charAt(rng.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }
}