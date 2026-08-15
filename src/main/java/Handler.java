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
        server.createContext("/qr", Handler::qr);
        server.createContext("/files", Handler::files);
        server.createContext("/info", Handler::info);
    }

    private static void home(HttpExchange exchange)
            throws IOException {

        serveResource(
                exchange,
                "web/index.html",
                "text/html; charset=UTF-8"
        );
    }

    private static void style(HttpExchange exchange)
            throws IOException {

        serveResource(
                exchange,
                "web/style.css",
                "text/css; charset=UTF-8"
        );
    }

    private static void serveResource(
            HttpExchange exchange,
            String resource,
            String contentType
    ) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

        InputStream input = Handler.class
                .getClassLoader()
                .getResourceAsStream(resource);

        if (input == null) {
            sendStatus(exchange, 404);
            return;
        }

        byte[] content;

        try (input) {
            content = input.readAllBytes();
        }

        exchange.getResponseHeaders().set(
                "Content-Type",
                contentType
        );

        exchange.sendResponseHeaders(200, content.length);

        try (OutputStream output =
                     exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void upload(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) {
            sendStatus(exchange, 405);
            return;
        }

        String rawName = queryParam(exchange, "name");

        String fileName = (rawName == null || rawName.isBlank())
                ? "file-" + System.currentTimeMillis()
                : sanitizeFileName(rawName);

        Files.createDirectories(SHARED_DIR);

        Path target = SHARED_DIR.resolve(fileName);

        try (
                InputStream input = exchange.getRequestBody();
                OutputStream output = Files.newOutputStream(target)
        ) {
            input.transferTo(output);
        }

        sendText(exchange, 200, "Upload successful!");
    }

    private static void download(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

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

        exchange.getResponseHeaders().set(
                "Content-Type",
                contentType != null ? contentType : "application/octet-stream"
        );

        exchange.getResponseHeaders().set(
                "Content-Disposition",
                "attachment; filename=\"" +
                        target.getFileName() +
                        "\""
        );

        exchange.sendResponseHeaders(
                200,
                Files.size(target)
        );

        try (
                InputStream input = Files.newInputStream(target);
                OutputStream output = exchange.getResponseBody()
        ) {
            input.transferTo(output);
        }
    }

    private static void qr(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

        String rawName = queryParam(exchange, "name");

        if (rawName == null || rawName.isBlank()) {
            sendStatus(exchange, 400);
            return;
        }

        String fileName = sanitizeFileName(rawName);

        if (!Files.exists(SHARED_DIR.resolve(fileName))) {
            sendStatus(exchange, 404);
            return;
        }

        String downloadUrl = "http://" +
                localLanAddress() + ":" +
                exchange.getLocalAddress().getPort() +
                "/download?name=" +
                URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        byte[] png;

        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    downloadUrl,
                    BarcodeFormat.QR_CODE,
                    240,
                    240
            );

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", buffer);
            png = buffer.toByteArray();

        } catch (WriterException e) {
            sendStatus(exchange, 500);
            return;
        }

        exchange.getResponseHeaders().set(
                "Content-Type",
                "image/png"
        );

        exchange.sendResponseHeaders(200, png.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(png);
        }
    }

    private static void files(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

        Files.createDirectories(SHARED_DIR);

        List<String> names;

        try (Stream<Path> stream = Files.list(SHARED_DIR)) {
            names = stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        }

        StringBuilder json = new StringBuilder("[");

        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"")
                    .append(names.get(i).replace("\"", ""))
                    .append("\"");
        }

        json.append("]");

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );

        sendText(exchange, 200, json.toString());
    }

    private static void info(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

        String json = String.format(
                "{\"ip\":\"%s\",\"port\":%d}",
                localLanAddress(),
                exchange.getLocalAddress().getPort()
        );

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );

        sendText(exchange, 200, json);
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

        if (query == null) {
            return null;
        }

        for (String pair : query.split("&")) {

            int eq = pair.indexOf('=');

            if (eq < 0) {
                continue;
            }

            String pairKey = URLDecoder.decode(
                    pair.substring(0, eq),
                    StandardCharsets.UTF_8
            );

            if (pairKey.equals(key)) {
                return URLDecoder.decode(
                        pair.substring(eq + 1),
                        StandardCharsets.UTF_8
                );
            }
        }

        return null;
    }

    private static String sanitizeFileName(String name) {

        String base = Path.of(name).getFileName().toString();

        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void sendText(
            HttpExchange exchange,
            int status,
            String text
    ) throws IOException {

        byte[] content =
                text.getBytes(StandardCharsets.UTF_8);

        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/plain; charset=UTF-8"
            );
        }

        exchange.sendResponseHeaders(
                status,
                content.length
        );

        try (OutputStream output =
                     exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendStatus(
            HttpExchange exchange,
            int status
    ) throws IOException {

        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
