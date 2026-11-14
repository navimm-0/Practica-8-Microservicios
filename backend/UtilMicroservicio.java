import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class UtilMicroservicio {
    public static final String TOKEN = "Bearer TOKEN_SECRETO_ESCOM_2026";
    public static final String REGISTRO_URL = "http://localhost:5100";
    private static final HttpClient client = HttpClient.newHttpClient();

    // Inicia el latido cada 2 segundos hacia el Registro
    public static void iniciarLatido(String tipo, String miUrl) {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                String payload = String.format("{\"tipo\":\"%s\",\"url\":\"%s\"}", tipo, miUrl);
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(REGISTRO_URL + "/registrar"))
                    .header("Authorization", TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
                client.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }, 0, 2, TimeUnit.SECONDS);
    }

    // Descubre dinámicamente la URL de otro microservicio consultando al Registro
    public static String descubrirServicio(String tipo) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REGISTRO_URL + "/servicios?tipo=" + tipo))
                .header("Authorization", TOKEN)
                .GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = res.body();
            int idx = body.indexOf("\"url\":\"");
            if (idx == -1) return null;
            idx += 7;
            return body.substring(idx, body.indexOf("\"", idx));
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean validarToken(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (!TOKEN.equals(auth)) {
            enviarJson(ex, 401, "{\"error\":\"Acceso denegado: Se requiere token interno\"}");
            return false;
        }
        return true;
    }

    public static void enviarJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}