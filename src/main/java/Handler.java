import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Handler {

    private static final Path SHARED_DIR = Path.of("shared");

    public static void register(HttpServer server) {
        server.createContext("/", Handler::home);
        server.createContext("/style.css", Handler::style);
        server.createContext("/upload", Handler::upload);
        server.createContext("/download", Handler::download);
        server.createContext("/download-all", Handler::downloadAll);
        server.createContext("/qr", Handler::qr);
        server.createContext("/files", Handler::files);
        server.createContext("/info", Handler::info);
    }

    private static void home(HttpExchange exchange) throws IOException {
        serveResource(exchange, "web/index.html", "text/html; charset=UTF-8");
    }

    private static void style(HttpExchange exchange) throws IOException {
        serveResource(exchange, "web/style.css", "text/css; charset=UTF-8");
    }

    private static void serveResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        InputStream input = Handler.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) {
            sendStatus(exchange, 404);
            return;
        }
        byte[] content;
        try (input) {
            content = input.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private static void upload(HttpExchange exchange) throws IOException {
        String rawName = queryParam(exchange, "name");
        String fileName = (rawName == null || rawName.isBlank())
                ? "file-" + System.currentTimeMillis()
                : sanitizeFileName(rawName);

        Files.createDirectories(SHARED_DIR);
        Path target = SHARED_DIR.resolve(fileName);

        try (InputStream in = exchange.getRequestBody();
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }

        sendText(exchange, 200, "Upload successful!");
    }

    private static void download(HttpExchange exchange) throws IOException {
        String rawName = queryParam(exchange, "name");
        if (rawName == null || rawName.isBlank()) {
            sendStatus(exchange, 400);
            return;
        }

        Path target = SHARED_DIR.resolve(sanitizeFileName(rawName));
        if (!Files.exists(target)) {
            sendStatus(exchange, 404);
            return;
        }

        String contentType = Files.probeContentType(target);
        exchange.getResponseHeaders().set("Content-Type", contentType != null ? contentType : "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + target.getFileName() + "\"");
        exchange.sendResponseHeaders(200, Files.size(target));

        try (InputStream in = Files.newInputStream(target);
             OutputStream out = exchange.getResponseBody()) {
            in.transferTo(out);
        }
    }

    // Page the QR code points to. Loops through every shared file and
    // triggers a normal browser download for each one.
    private static void downloadAll(HttpExchange exchange) throws IOException {
        List<String> names = sharedFileNames();

        StringBuilder links = new StringBuilder();
        for (String name : names) {
            links.append("'").append(name.replace("'", "\\'")).append("',");
        }

        String html =
                "<!DOCTYPE html><html><body style='font-family:sans-serif;text-align:center;padding:40px'>" +
                        "<p>Downloading " + names.size() + " file(s)...</p>" +
                        "<script>" +
                        "var files = [" + links + "];" +
                        "for (var i = 0; i < files.length; i++) {" +
                        "  var a = document.createElement('a');" +
                        "  a.href = '/download?name=' + encodeURIComponent(files[i]);" +
                        "  a.download = files[i];" +
                        "  document.body.appendChild(a);" +
                        "  a.click();" +
                        "}" +
                        "</script>" +
                        "</body></html>";

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        sendText(exchange, 200, html);
    }

    private static void qr(HttpExchange exchange) throws IOException {
        String url = "http://" + localLanAddress() + ":" + exchange.getLocalAddress().getPort() + "/download-all";

        byte[] png;
        try {
            BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 240, 240);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", buffer);
            png = buffer.toByteArray();
        } catch (WriterException e) {
            sendStatus(exchange, 500);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, png.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(png);
        }
    }

    private static void files(HttpExchange exchange) throws IOException {
        List<String> names = sharedFileNames();

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(names.get(i).replace("\"", "")).append("\"");
        }
        json.append("]");

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        sendText(exchange, 200, json.toString());
    }

    private static void info(HttpExchange exchange) throws IOException {
        String json = String.format("{\"ip\":\"%s\",\"port\":%d}", localLanAddress(), exchange.getLocalAddress().getPort());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        sendText(exchange, 200, json);
    }

    private static List<String> sharedFileNames() throws IOException {
        Files.createDirectories(SHARED_DIR);
        try (Stream<Path> stream = Files.list(SHARED_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String localLanAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return socket.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            return "localhost";
        }
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String pairKey = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (pairKey.equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String sanitizeFileName(String name) {
        String base = Path.of(name).getFileName().toString();
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        }
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}