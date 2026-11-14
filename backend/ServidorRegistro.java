import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ServidorRegistro {
    private static final String TOKEN = "Bearer TOKEN_SECRETO_ESCOM_2026";
    static class Nodo {
        String tipo;
        String url;
        long ultimoLatido;
        Nodo(String tipo, String url) {
            this.tipo = tipo;
            this.url = url;
            this.ultimoLatido = System.currentTimeMillis();
        }
    }

    private static final Map<String, Nodo> directorio = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        // Hilo limpiador: elimina servicios que no mandan latido en 6 segundos
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long ahora = System.currentTimeMillis();
            directorio.entrySet().removeIf(entry -> {
                boolean expirado = (ahora - entry.getValue().ultimoLatido) > 6000;
                if (expirado) System.out.println("[REGISTRO] Servicio caído eliminado: " + entry.getKey());
                return expirado;
            });
        }, 2, 2, TimeUnit.SECONDS);

        HttpServer server = HttpServer.create(new InetSocketAddress(5100), 0);

        server.createContext("/registrar", exchange -> {
            if (!validarToken(exchange)) return;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String tipo = extraer(body, "tipo");
                String url = extraer(body, "url");
                String clave = tipo + "@" + url;
                if (!directorio.containsKey(clave)) {
                    System.out.println("[REGISTRO] Nuevo servicio en línea: " + tipo + " -> " + url);
                }
                directorio.put(clave, new Nodo(tipo, url));
                responder(exchange, 200, "{\"estado\":\"registrado\"}");
            }
        });

        server.createContext("/servicios", exchange -> {
            if (!validarToken(exchange)) return;
            String query = exchange.getRequestURI().getQuery();
            String filtroTipo = (query != null && query.startsWith("tipo=")) ? query.substring(5) : "";

            StringBuilder json = new StringBuilder("[");
            boolean primero = true;
            for (Nodo n : directorio.values()) {
                if (filtroTipo.isEmpty() || n.tipo.equalsIgnoreCase(filtroTipo)) {
                    if (!primero) json.append(",");
                    json.append(String.format("{\"tipo\":\"%s\",\"url\":\"%s\"}", n.tipo, n.url));
                    primero = false;
                }
            }
            json.append("]");
            responder(exchange, 200, json.toString());
        });

        server.start();
        System.out.println("[REGISTRO] Directorio de Microservicios activo en :5100");
    }

    private static boolean validarToken(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (!TOKEN.equals(auth)) {
            responder(ex, 401, "{\"error\":\"Token interno inválido\"}");
            return false;
        }
        return true;
    }

    private static void responder(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String extraer(String json, String clave) {
        String p = "\"" + clave + "\":\"";
        int i = json.indexOf(p);
        if (i == -1) return "";
        i += p.length();
        return json.substring(i, json.indexOf("\"", i));
    }
}