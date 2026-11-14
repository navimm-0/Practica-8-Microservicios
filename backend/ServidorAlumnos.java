import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ServidorAlumnos {
    static class Alumno {
        int id; String nombre; String universidad;
        Alumno(int id, String n, String u) { this.id = id; this.nombre = n; this.universidad = u; }
    }

    private static final List<Alumno> alumnosDB = Collections.synchronizedList(new ArrayList<>(Arrays.asList(
        new Alumno(101, "Carlos Pérez", "ESCOM - IPN"),
        new Alumno(201, "Ana Gómez", "UPIITA - IPN")
    )));

    public static void main(String[] args) throws Exception {
        UtilMicroservicio.iniciarLatido("alumnos", "http://localhost:8001");
        HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);

        server.createContext("/alumnos", ex -> {
            if (!UtilMicroservicio.validarToken(ex)) return;
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equalsIgnoreCase(method)) {
                String[] parts = path.split("/");
                if (parts.length > 2) {
                    int id = Integer.parseInt(parts[2]);
                    Optional<Alumno> al = alumnosDB.stream().filter(a -> a.id == id).findFirst();
                    if (al.isPresent()) {
                        Alumno a = al.get();
                        UtilMicroservicio.enviarJson(ex, 200, String.format("{\"id\":%d,\"nombre\":\"%s\",\"universidad\":\"%s\"}", a.id, a.nombre, a.universidad));
                    } else {
                        UtilMicroservicio.enviarJson(ex, 404, "{\"error\":\"Alumno no encontrado\"}");
                    }
                } else {
                    StringBuilder sb = new StringBuilder("[");
                    synchronized (alumnosDB) {
                        for (int i = 0; i < alumnosDB.size(); i++) {
                            Alumno a = alumnosDB.get(i);
                            sb.append(String.format("{\"id\":%d,\"nombre\":\"%s\",\"universidad\":\"%s\"}", a.id, a.nombre, a.universidad));
                            if (i < alumnosDB.size() - 1) sb.append(",");
                        }
                    }
                    sb.append("]");
                    UtilMicroservicio.enviarJson(ex, 200, sb.toString());
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                try {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int id = Integer.parseInt(extraerNum(body, "id"));
                    String nombre = extraerStr(body, "nombre");
                    String uni = extraerStr(body, "universidad");

                    if (id <= 0 || nombre.isEmpty() || uni.isEmpty()) {
                        UtilMicroservicio.enviarJson(ex, 400, "{\"error\":\"Todos los campos son obligatorios\"}");
                        return;
                    }

                    synchronized (alumnosDB) {
                        boolean existe = alumnosDB.stream().anyMatch(a -> a.id == id);
                        if (existe) {
                            UtilMicroservicio.enviarJson(ex, 400, "{\"error\":\"Ya existe un alumno con ese ID\"}");
                            return;
                        }
                        alumnosDB.add(new Alumno(id, nombre, uni));
                    }
                    UtilMicroservicio.enviarJson(ex, 201, "{\"mensaje\":\"Alumno registrado\"}");
                } catch (Exception e) {
                    e.printStackTrace();
                    UtilMicroservicio.enviarJson(ex, 400, "{\"error\":\"Error al procesar JSON del alumno\"}");
                }
            }
        });

        server.start();
        System.out.println("[ALUMNOS] Microservicio activo en :8001");
    }

    private static String extraerNum(String j, String k) {
        String patron = "\"" + k + "\"";
        int idx = j.indexOf(patron);
        if (idx == -1) return "0";
        int dosPuntos = j.indexOf(":", idx + patron.length());
        if (dosPuntos == -1) return "0";
        int finComa = j.indexOf(",", dosPuntos);
        int finLlave = j.indexOf("}", dosPuntos);
        int fin = (finComa != -1 && finComa < finLlave) ? finComa : finLlave;
        if (fin == -1) return "0";
        String val = j.substring(dosPuntos + 1, fin).replaceAll("[^0-9]", "");
        return val.isEmpty() ? "0" : val;
    }

    private static String extraerStr(String j, String k) {
        String patron = "\"" + k + "\"";
        int idx = j.indexOf(patron);
        if (idx == -1) return "";
        int dosPuntos = j.indexOf(":", idx + patron.length());
        int comillaInicio = j.indexOf("\"", dosPuntos + 1);
        if (comillaInicio == -1) return "";
        int comillaFin = j.indexOf("\"", comillaInicio + 1);
        if (comillaFin == -1) return "";
        return j.substring(comillaInicio + 1, comillaFin);
    }
}