import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class Handler {

    public static void register(HttpServer server) {

        server.createContext("/", Handler::home);
        server.createContext("/style.css", Handler::style);
        server.createContext("/download", Handler::download);
        server.createContext("/upload", Handler::upload);
    }


    // =========================
    // Home Page
    // =========================

    private static void home(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

        Path file = Path.of("src/web/index.html");

        if (!Files.exists(file)) {
            System.out.println("index.html not found at: "
                    + file.toAbsolutePath());

            sendStatus(exchange, 404);
            return;
        }

        byte[] content = Files.readAllBytes(file);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/html; charset=UTF-8"
        );

        exchange.sendResponseHeaders(200, content.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }


    // =========================
    // CSS
    // =========================

    private static void style(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

        Path file = Path.of("src/web/style.css");

        if (!Files.exists(file)) {
            System.out.println("style.css not found at: "
                    + file.toAbsolutePath());

            sendStatus(exchange, 404);
            return;
        }

        byte[] content = Files.readAllBytes(file);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/css; charset=UTF-8"
        );

        exchange.sendResponseHeaders(200, content.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }


    // =========================
    // Download
    // =========================

    private static void download(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            sendStatus(exchange, 405);
            return;
        }

        System.out.println(
                "Client: " +
                        exchange.getRemoteAddress()
        );

        File file = new File("Secrets.txt");

        if (!file.exists()) {
            System.out.println(
                    "File not found at: " +
                            file.getAbsolutePath()
            );

            sendStatus(exchange, 404);
            return;
        }

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/octet-stream"
        );

        exchange.getResponseHeaders().set(
                "Content-Disposition",
                "attachment; filename=\"" +
                        file.getName() +
                        "\""
        );

        exchange.sendResponseHeaders(
                200,
                file.length()
        );

        try (
                InputStream in =
                        new FileInputStream(file);

                OutputStream out =
                        exchange.getResponseBody()
        ) {
            in.transferTo(out);
        }
    }


    // =========================
    // Upload
    // =========================

    private static void upload(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) {
            sendStatus(exchange, 405);
            return;
        }

        Path file = Path.of("shared/uploaded-file");

        try (
                InputStream in = exchange.getRequestBody();
                OutputStream out = Files.newOutputStream(file)
        ) {
            in.transferTo(out);
        }

        String response = "Upload successful!";

        exchange.sendResponseHeaders(200, response.length());

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.getBytes());
        }
    }


    // =========================
    // Utility
    // =========================

    private static void sendStatus(
            HttpExchange exchange,
            int status
    ) throws IOException {

        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}