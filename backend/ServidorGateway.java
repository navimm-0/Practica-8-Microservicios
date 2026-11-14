import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;

public class ServidorGateway {
    private static final HttpClient client = HttpClient.newHttpClient();
    
    // Estado del Disyuntor (Circuit Breaker) para el servicio de Préstamos
    private static int fallosConsecutivos = 0;
    private static long circuitoAbiertoHasta = 0;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Endpoint de Salud y Estado del Disyuntor
        server.createContext("/api/v1/salud", ex -> {
            cors(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { UtilMicroservicio.enviarJson(ex, 204, ""); return; }
            boolean alumnosOk = UtilMicroservicio.descubrirServicio("alumnos") != null;
            boolean librosOk = UtilMicroservicio.descubrirServicio("libros") != null;
            boolean prestamosOk = UtilMicroservicio.descubrirServicio("prestamos") != null;
            boolean disyuntorAbierto = System.currentTimeMillis() < circuitoAbiertoHasta;

            String estadoGlobal = (alumnosOk && librosOk && prestamosOk && !disyuntorAbierto) ? "OK" : "DEGRADADO";
            String json = String.format(
                "{\"estado\":\"%s\",\"disyuntor\":\"%s\",\"servicios\":{\"registro\":true,\"alumnos\":%b,\"libros\":%b,\"prestamos\":%b}}",
                estadoGlobal, disyuntorAbierto ? "ABIERTO" : "CERRADO", alumnosOk, librosOk, prestamosOk
            );
            UtilMicroservicio.enviarJson(ex, 200, json);
        });

        // Enrutador hacia Alumnos
        server.createContext("/api/v1/alumnos", ex -> {
            cors(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { UtilMicroservicio.enviarJson(ex, 204, ""); return; }
            String url = UtilMicroservicio.descubrirServicio("alumnos");
            if (url == null) { UtilMicroservicio.enviarJson(ex, 503, "{\"error\":\"Servicio Alumnos caído\"}"); return; }
            try {
                reenviar(ex, url + "/alumnos", null);
            } catch (Exception e) {
                UtilMicroservicio.enviarJson(ex, 502, "{\"error\":\"Error al comunicar con Alumnos\"}");
            }
        });

        // Enrutador hacia Libros
        server.createContext("/api/v1/libros", ex -> {
            cors(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { UtilMicroservicio.enviarJson(ex, 204, ""); return; }
            String url = UtilMicroservicio.descubrirServicio("libros");
            if (url == null) { UtilMicroservicio.enviarJson(ex, 503, "{\"error\":\"Servicio Libros caído\"}"); return; }
            String subruta = ex.getRequestURI().getPath().replace("/api/v1/libros", "/libros");
            try {
                reenviar(ex, url + subruta, null);
            } catch (Exception e) {
                UtilMicroservicio.enviarJson(ex, 502, "{\"error\":\"Error al comunicar con Libros\"}");
            }
        });

        // Enrutador hacia Préstamos protegido con Disyuntor (Circuit Breaker)
        server.createContext("/api/v1/prestamos", ex -> {
            cors(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { UtilMicroservicio.enviarJson(ex, 204, ""); return; }

            // 1. Verificar si el disyuntor está abierto
            if (System.currentTimeMillis() < circuitoAbiertoHasta) {
                ex.getResponseHeaders().add("Retry-After", "5");
                UtilMicroservicio.enviarJson(ex, 503, "{\"error\":\"Disyuntor ABIERTO: Servicio de Préstamos temporalmente aislado\"}");
                return;
            }

            // 2. Descubrir servicio en el Registro
            String url = UtilMicroservicio.descubrirServicio("prestamos");
            if (url == null) {
                registrarFallo();
                ex.getResponseHeaders().add("Retry-After", "5");
                UtilMicroservicio.enviarJson(ex, 503, "{\"error\":\"Microservicio Préstamos no disponible\"}");
                return;
            }

            try {
                String idemKey = ex.getRequestHeaders().getFirst("Idempotency-Key");
                reenviar(ex, url + "/prestamos", idemKey);
                fallosConsecutivos = 0; // Resetear disyuntor tras éxito
            } catch (Exception e) {
                registrarFallo();
                ex.getResponseHeaders().add("Retry-After", "5");
                UtilMicroservicio.enviarJson(ex, 503, "{\"error\":\"Fallo de conexión con Préstamos\"}");
            }
        });

        server.start();
        System.out.println("[GATEWAY] API Gateway público activo en :8080");
    }

    private static void registrarFallo() {
        fallosConsecutivos++;
        if (fallosConsecutivos >= 3) {
            circuitoAbiertoHasta = System.currentTimeMillis() + 5000; // Abre 5 segundos
            System.out.println("[GATEWAY] ¡DISYUNTOR ABIERTO por 5 segundos tras 3 fallos!");
        }
    }

    private static void reenviar(HttpExchange ex, String destino, String idemKey) throws Exception {
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(destino))
            .header("Authorization", UtilMicroservicio.TOKEN);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);

        if ("POST".equalsIgnoreCase(method)) b.POST(HttpRequest.BodyPublishers.ofString(body));
        else if ("PUT".equalsIgnoreCase(method)) b.PUT(HttpRequest.BodyPublishers.noBody());
        else b.GET();

        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.headers().firstValue("Idempotent-Replayed").isPresent()) {
            ex.getResponseHeaders().add("Idempotent-Replayed", "true");
        }
        UtilMicroservicio.enviarJson(ex, res.statusCode(), res.body());
    }

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        ex.getResponseHeaders().add("Access-Control-Expose-Headers", "Idempotent-Replayed, Retry-After");
    }
}