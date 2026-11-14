import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ServidorLibros {
    static class Libro {
        int id; String titulo; boolean disponible;
        Libro(int id, String t, boolean d) { this.id = id; this.titulo = t; this.disponible = d; }
    }

    private static final List<Libro> librosDB = Collections.synchronizedList(new ArrayList<>(Arrays.asList(
        new Libro(1, "Sistemas Distribuidos - Coulouris", true),
        new Libro(2, "Redes de Computadoras - Tanenbaum", true),
        new Libro(3, "Sistemas Operativos Modernos", true)
    )));

    public static void main(String[] args) throws Exception {
        UtilMicroservicio.iniciarLatido("libros", "http://localhost:6001");
        HttpServer server = HttpServer.create(new InetSocketAddress(6001), 0);

        server.createContext("/libros", ex -> {
            if (!UtilMicroservicio.validarToken(ex)) return;
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equalsIgnoreCase(method)) {
                StringBuilder sb = new StringBuilder("[");
                synchronized (librosDB) {
                    for (int i = 0; i < librosDB.size(); i++) {
                        Libro l = librosDB.get(i);
                        sb.append(String.format("{\"id\":%d,\"titulo\":\"%s\",\"disponible\":%b}", l.id, l.titulo, l.disponible));
                        if (i < librosDB.size() - 1) sb.append(",");
                    }
                }
                sb.append("]");
                UtilMicroservicio.enviarJson(ex, 200, sb.toString());
            } else if ("POST".equalsIgnoreCase(method)) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int id = Integer.parseInt(extraerNum(body, "id"));
                String titulo = extraerStr(body, "titulo");
                librosDB.add(new Libro(id, titulo, true));
                UtilMicroservicio.enviarJson(ex, 201, "{\"mensaje\":\"Libro agregado\"}");
            } else if ("PUT".equalsIgnoreCase(method)) {
                String[] parts = path.split("/");
                int idLibro = Integer.parseInt(parts[2]);
                synchronized (librosDB) { // Control atómico de concurrencia
                    Optional<Libro> opt = librosDB.stream().filter(l -> l.id == idLibro).findFirst();
                    if (opt.isEmpty()) {
                        UtilMicroservicio.enviarJson(ex, 404, "{\"error\":\"Libro no existe\"}");
                        return;
                    }
                    Libro l = opt.get();
                    if (path.endsWith("/reservar")) {
                        if (!l.disponible) {
                            UtilMicroservicio.enviarJson(ex, 400, "{\"error\":\"El libro ya fue prestado\"}");
                            return;
                        }
                        l.disponible = false;
                        UtilMicroservicio.enviarJson(ex, 200, String.format("{\"titulo\":\"%s\"}", l.titulo));
                    } else if (path.endsWith("/liberar")) {
                        l.disponible = true;
                        UtilMicroservicio.enviarJson(ex, 200, "{\"mensaje\":\"Libro devuelto\"}");
                    }
                }
            }
        });

        server.start();
        System.out.println("[LIBROS] Microservicio activo en :6001");
    }

    private static String extraerNum(String j, String k) {
        int i = j.indexOf("\"" + k + "\":") + k.length() + 3;
        int f = j.indexOf(",", i) == -1 ? j.indexOf("}", i) : j.indexOf(",", i);
        return j.substring(i, f).replaceAll("[^0-9]", "");
    }
    private static String extraerStr(String j, String k) {
        int i = j.indexOf("\"" + k + "\":\"") + k.length() + 4;
        return j.substring(i, j.indexOf("\"", i));
    }
}