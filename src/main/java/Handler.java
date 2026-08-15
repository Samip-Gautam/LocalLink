import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Enumeration;

public class Handler {

    private static final Path FILE =
            Path.of("shared/uploaded-file");

    public static void register(HttpServer server) {

        server.createContext("/", Handler::home);
        server.createContext("/style.css", Handler::style);
        server.createContext("/upload", Handler::upload);
        server.createContext("/download", Handler::download);
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

        Files.createDirectories(FILE.getParent());

        try (
                InputStream input = exchange.getRequestBody();
                OutputStream output = Files.newOutputStream(FILE)
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

        if (!Files.exists(FILE)) {
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
                        FILE.getFileName() +
                        "\""
        );

        exchange.sendResponseHeaders(
                200,
                Files.size(FILE)
        );

        try (
                InputStream input = Files.newInputStream(FILE);
                OutputStream output = exchange.getResponseBody()
        ) {
            input.transferTo(output);
        }
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

        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface network =
                        interfaces.nextElement();

                if (!network.isUp()
                        || network.isLoopback()
                        || network.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                        network.getInetAddresses();

                while (addresses.hasMoreElements()) {

                    InetAddress address =
                            addresses.nextElement();

                    if (address instanceof Inet4Address
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }

        } catch (IOException ignored) {
        }

        return "localhost";
    }

    private static void sendText(
            HttpExchange exchange,
            int status,
            String text
    ) throws IOException {

        byte[] content =
                text.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/plain; charset=UTF-8"
        );

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