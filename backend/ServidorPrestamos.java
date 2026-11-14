import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class ServidorPrestamos {
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Map<String, String> cacheIdempotencia = new ConcurrentHashMap<>();
    private static final List<String> historialPrestamos = Collections.synchronizedList(new ArrayList<>());
    private static int contadorFolio = 1000;

    public static void main(String[] args) throws Exception {
        UtilMicroservicio.iniciarLatido("prestamos", "http://localhost:7001");
        HttpServer server = HttpServer.create(new InetSocketAddress(7001), 0);

        server.createContext("/prestamos", ex -> {
            if (!UtilMicroservicio.validarToken(ex)) return;
            String method = ex.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                String json = "[" + String.join(",", historialPrestamos) + "]";
                UtilMicroservicio.enviarJson(ex, 200, json);
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                String idemKey = ex.getRequestHeaders().getFirst("Idempotency-Key");
                if (idemKey != null && cacheIdempotencia.containsKey(idemKey)) {
                    ex.getResponseHeaders().add("Idempotent-Replayed", "true");
                    UtilMicroservicio.enviarJson(ex, 200, cacheIdempotencia.get(idemKey));
                    return;
                }

                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int idAlumno = Integer.parseInt(extraerNum(body, "idAlumno"));
                int idLibro = Integer.parseInt(extraerNum(body, "idLibro"));

                // Descubrimiento dinámico en el Registro
                String urlAlumnos = UtilMicroservicio.descubrirServicio("alumnos");
                String urlLibros = UtilMicroservicio.descubrirServicio("libros");

                if (urlAlumnos == null || urlLibros == null) {
                    UtilMicroservicio.enviarJson(ex, 503, "{\"error\":\"Servicios internos no disponibles en el Registro\"}");
                    return;
                }

                try {
                    // 1. Validar alumno
                    HttpResponse<String> resAl = llamarInterno(urlAlumnos + "/alumnos/" + idAlumno, "GET", null);
                    if (resAl.statusCode() == 404) {
                        UtilMicroservicio.enviarJson(ex, 404, "{\"error\":\"Alumno no encontrado en el padrón\"}");
                        return;
                    }

                    // 2. Reservar libro atómicamente
                    HttpResponse<String> resLib = llamarInterno(urlLibros + "/libros/" + idLibro + "/reservar", "PUT", null);
                    if (resLib.statusCode() != 200) {
                        UtilMicroservicio.enviarJson(ex, resLib.statusCode(), resLib.body());
                        return;
                    }

                    String nombreAlumno = extraerStr(resAl.body(), "nombre");
                    String tituloLibro = extraerStr(resLib.body(), "titulo");
                    String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    int folio = ++contadorFolio;

                    String registroJson = String.format(
                        "{\"folio\":\"FOL-%d\",\"alumno\":\"%s\",\"idLibro\":%d,\"libro\":\"%s\",\"fecha\":\"%s\"}",
                        folio, nombreAlumno, idLibro, tituloLibro, fecha
                    );

                    historialPrestamos.add(0, registroJson);
                    if (idemKey != null) cacheIdempotencia.put(idemKey, registroJson);

                    UtilMicroservicio.enviarJson(ex, 201, registroJson);
                } catch (Exception e) {
                    UtilMicroservicio.enviarJson(ex, 500, "{\"error\":\"Fallo en transacción distribuida\"}");
                }
            }
        });

        server.start();
        System.out.println("[PRESTAMOS] Microservicio de Negocio activo en :7001");
    }

    private static HttpResponse<String> llamarInterno(String url, String method, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url))
            .header("Authorization", UtilMicroservicio.TOKEN);
        if ("PUT".equals(method)) b.PUT(HttpRequest.BodyPublishers.noBody());
        else b.GET();
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
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